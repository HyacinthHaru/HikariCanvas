package ac.haru.hikaricanvas.image;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared image magic-byte sniffer and isolated {@link ImageIO} decoder with a 200ms
 * cooperative execution timeout and 8192px header dimension guard.
 *
 * <p>Used by both HTTP image uploads ({@link UploadHandler}) and {@code .canvas} archive
 * asset ingestion ({@code canvasfile.AssetIngest}) to defend against decompression bombs
 * and hung {@link ImageIO} reader loops.</p>
 */
public final class ImageDecodeGuard {

    /** Per-image active decode timeout in milliseconds (excludes queue wait). */
    public static final long DECODE_TIMEOUT_MS = 200L;

    /** Maximum width or height allowed before raster allocation. */
    public static final int BBOX_MAX_EDGE = 8192;

    /** Maximum wait in queue before a queued decode task is aborted as backlogged. */
    private static final long QUEUE_WAIT_MS = 5_000L;

    private final ExecutorService decoderPool;

    public ImageDecodeGuard() {
        this.decoderPool = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "hc-image-decoder");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Detects actual image MIME type from magic bytes ({@code image/png}, {@code image/jpeg},
     * or {@code image/webp}); returns {@code null} if unknown or shorter than 12 bytes.
     */
    public static String detectMagicMime(byte[] b) {
        if (b == null || b.length < 12) return null;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        // WEBP: 'RIFF' .... 'WEBP'
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Decodes {@code bytes} on the isolated {@code hc-image-decoder} daemon thread with a
     * {@value #DECODE_TIMEOUT_MS}ms active execution timeout and cooperative
     * {@link ImageReader#abort()} cancellation.
     *
     * @throws TimeoutException if active decoding exceeds {@value #DECODE_TIMEOUT_MS}ms
     * @throws IOException      if the decoder queue is full/shut down, dimensions exceed
     *                          {@value #BBOX_MAX_EDGE}, or decoding fails
     */
    public BufferedImage decodeWithTimeout(byte[] bytes) throws Exception {
        final AtomicReference<ImageReader> readerRef = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        Future<BufferedImage> fut;
        try {
            fut = decoderPool.submit((Callable<BufferedImage>) () -> {
                started.countDown();
                return decodeCooperative(bytes, readerRef);
            });
        } catch (RejectedExecutionException ree) {
            throw new IOException("image decoder busy; retry later", ree);
        }
        try {
            if (!started.await(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                fut.cancel(true);
                throw new IOException("image decoder queue backlogged");
            }
            return fut.get(DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            ImageReader r = readerRef.get();
            if (r != null) {
                try {
                    r.abort();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
            fut.cancel(true);
            throw te;
        }
    }

    /** Shuts down the underlying daemon decoder thread (idempotent). */
    public void shutdown() {
        decoderPool.shutdownNow();
    }

    private static BufferedImage decodeCooperative(byte[] bytes, AtomicReference<ImageReader> readerRef)
            throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            ImageReader reader = it.next();
            readerRef.set(reader);
            try {
                reader.setInput(iis, true, true);
                int pw = reader.getWidth(0);
                int ph = reader.getHeight(0);
                if (pw > BBOX_MAX_EDGE || ph > BBOX_MAX_EDGE) {
                    throw new IOException("image dimensions too large: " + pw + "x" + ph
                            + " > " + BBOX_MAX_EDGE);
                }
                return reader.read(0);
            } catch (IIOException abortOrFail) {
                throw new IOException("decode aborted or failed", abortOrFail);
            } finally {
                readerRef.set(null);
                reader.dispose();
            }
        }
    }
}

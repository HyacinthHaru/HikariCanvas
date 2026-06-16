/** 把字节数组作为文件触发浏览器下载。纯浏览器，不经服务器。 */
export function downloadBlob(bytes: Uint8Array, filename: string, mime: string): void {
    // TS 6 / 新 lib-dom 把 Uint8Array 泛化成 Uint8Array<ArrayBufferLike>，不再直接满足
    // BlobPart（BufferSource）。显式 cast 为 BlobPart——运行时 Uint8Array 本就是合法 BlobPart，
    // 保留 byteOffset/byteLength 语义（优于 .buffer，后者会丢 view 偏移）。
    const blob = new Blob([bytes as BlobPart], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
}

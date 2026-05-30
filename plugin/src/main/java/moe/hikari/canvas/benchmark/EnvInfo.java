package moe.hikari.canvas.benchmark;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 产出报告的运行环境快照——让服主知道这组数字是「哪台机器、哪个 JVM」跑出来的。
 *
 * <p>「数据透明」原则的一部分（{@code PROPOSAL.md §2.1}）：benchmark 数字脱离硬件 / 堆大小 /
 * GC 算法毫无意义，报告必须把环境摊开。本类只<b>读</b> JVM / OS 公开属性，无副作用、headless。</p>
 *
 * @param javaVersion         {@code java.version}
 * @param jvmName             {@code java.vm.name}
 * @param osName              {@code os.name}
 * @param osArch              {@code os.arch}
 * @param availableProcessors {@code Runtime.availableProcessors()}
 * @param maxHeapMb           {@code -Xmx}（MB）；无上限时 -1
 * @param gcNames             激活的 GC collector 名（如 {@code G1 Young Generation}）
 */
public record EnvInfo(
        String javaVersion, String jvmName, String osName, String osArch,
        int availableProcessors, long maxHeapMb, List<String> gcNames
) {
    public EnvInfo {
        gcNames = gcNames == null ? List.of() : List.copyOf(gcNames);
    }

    /** 现场抓取当前 JVM / OS 环境。 */
    public static EnvInfo capture() {
        Runtime rt = Runtime.getRuntime();
        long maxHeap = rt.maxMemory();
        long maxHeapMb = maxHeap == Long.MAX_VALUE ? -1 : maxHeap / (1024 * 1024);
        List<String> gc = new ArrayList<>();
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.add(bean.getName());
        }
        return new EnvInfo(
                System.getProperty("java.version", "?"),
                System.getProperty("java.vm.name", "?"),
                System.getProperty("os.name", "?"),
                System.getProperty("os.arch", "?"),
                rt.availableProcessors(), maxHeapMb, gc);
    }
}

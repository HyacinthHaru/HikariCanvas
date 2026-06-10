package moe.hikari.canvas.script.engine;

import java.util.List;
import java.util.function.Consumer;

/**
 * 0.7.0-P3 A2（K11）：{@code script.test} 试跑入口 seam——dispatcher 与
 * {@link ScriptRunner} 之间的异步桥（取代 P1 的同步 ScriptTestSeam：同步等待会
 * 阻塞 Jetty worker，合法规则可串 wait 至分钟级）。
 *
 * <p>生产装配（HikariCanvas onEnable）：按 wallId / ruleId 查 ScriptStore，
 * 经 {@link ScriptRunner#submit(String, moe.hikari.canvas.script.ScriptRule,
 * TriggerContext, Consumer)} 投递（{@code TriggerContext(TEST, 0, "test")}——
 * K12：TEST run 不豁免 Budget）。callback 契约见 submit javadoc：恰一次
 * （shutdown 竞态例外）。</p>
 */
public interface ScriptTestLauncher {

    /**
     * 发起一次试跑。
     *
     * @param wallId        规则归属 wall（dispatcher 已确认 ruleId 属本 wall）
     * @param ruleId        规则 id
     * @param traceCallback 轨迹回调（runner 线程触发恰一次；规则在 find 与 launch
     *                      之间被并发删时由实现侧回 error step）
     */
    void launch(String wallId, String ruleId, Consumer<List<TraceStep>> traceCallback);
}

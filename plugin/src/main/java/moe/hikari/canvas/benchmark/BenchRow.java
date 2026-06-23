package moe.hikari.canvas.benchmark;

/** benchmark 一行结果：x 轴值（动作数 / 活跃墙数）+ 其耗时分位。文案由调用端按 locale 组装。 */
public record BenchRow(int n, Percentiles p) {}

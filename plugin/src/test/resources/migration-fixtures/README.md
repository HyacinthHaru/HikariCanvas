# 迁移 fixture 测试

1.0 起每个新 migration（V018+）须配 fixture 测试（data-model.md §6.6.3），证明"应用迁移后旧数据无损 + 新结构正确"。

## 怎么加（以 V018 为例）
1. 新建 `migration-fixtures/V018__<name>/before.sql`——只放**种子数据 INSERT**（schema 由基类 runUpTo(17) 建好）。
2. 新建 `V018<Name>FixtureTest extends MigrationFixtureTestBase`，覆写 `targetVersion()=18` + `fixtureName()="V018__<name>"`，断言迁移后数据无损 / 新列默认值 / 新表存在。

基类 `MigrationFixtureTestBase` 流程：runUpTo(baseline) → 灌 before.sql → runUpTo(target) → 子类断言。

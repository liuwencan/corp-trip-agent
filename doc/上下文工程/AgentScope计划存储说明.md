# AgentScope 计划存储

适配 AgentScope **1.0.12** 的 `PlanStorage`，两个实现均位于 `cn.lwccxy.trip.agent.store`，支持 `addPlan`、`getPlan`、`getPlans`。相同 ID 原子覆盖，不存在的计划返回空 `Mono`，没有计划时返回空列表。

| 实现 | Spring Bean 名称 | 存储位置 |
| --- | --- | --- |
| `MysqlPlanStorage` | `mysqlPlanStorage` | 当前数据源的 `agentscope_plans` 表 |
| `RedisPlanStorage` | `redisPlanStorage` | 当前 Redis 数据库中的 `agentscope:plans` Hash，field 为计划 ID |

## 使用

在需要的构造参数上用 `@Qualifier("mysqlPlanStorage") PlanStorage planStorage` 选择 MySQL，或者用 `@Qualifier("redisPlanStorage") PlanStorage planStorage` 选择 Redis，再传给 SDK：

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .storage(planStorage)
        .build();
```

本次仅提供组件，未修改现有 Agent 的存储选择。两个组件同时存在，不设置默认的 `@Primary`。

SDK 的计划 ID、状态、完成时间等字段带有 `JsonIgnore`；`PlanSnapshot` 显式转换全部字段，避免直接序列化 SDK 模型丢失数据。恢复的子任务列表保持可变，可继续调用 SDK 的计划修订工具。

## 数据库部署

1. 新环境执行已包含 `agentscope_plans` 的 `sql/init.sql`。
2. 已存在环境先检查是否有同名表（如有，执行 `SHOW CREATE TABLE agentscope_plans` 核对结构），备份数据库；按现有版本迁移顺序，在 `V20261116__add_output_message_to_agent_trace.sql` 后执行 `sql/migration/V20261117__create_agentscope_plans.sql`。
3. 记录执行结果，确认表包含 `plan_id`、`plan_data`、`created_at`、`updated_at`，且 `plan_id` 主键区分大小写，再启用 MySQL 实现。

迁移仅新增计划表，不修改其他业务表，也不在应用启动时自动执行。回滚时先停用 MySQL 计划存储并备份计划数据，再按迁移脚本中的说明删除该表；删除表会丢失历史计划。本次未在实际数据库执行迁移。

## 存储语义

- 数据访问在订阅时执行，阻塞式 MyBatis/Redis 调用调度到 `boundedElastic`；异常通过 `Mono` 传播。
- 两个实现是独立后端，不进行 MySQL/Redis 双写或缓存同步。
- 两个默认组件的历史列表均为共享范围，直接替换多用户 Agent 的存储前，应由调用方补充会话或用户隔离；`PlanStorage` 本身没有用户参数。
- 保存的是调用 `addPlan` 时的快照。修改已保存的 Java 对象后需再次保存；SDK 1.0.12 在结束计划等归档路径中调用 `addPlan`，此接口不代表每次子任务修改都会自动落库。
- Redis 不主动设置 TTL，实例重启后的恢复能力取决于 Redis 的 RDB/AOF 配置。`getPlans` 一次返回全部计划，适合历史数量可控的场景。

## 验证

`src/test/java/cn/lwccxy/trip/agent/store/PlanStorageTest.java` 对两个后端执行相同契约测试：MySQL 使用真实 MyBatis Mapper 和 H2 MySQL 模式，Redis 使用字符串 Hash 模板替身。另有 `PlanStorageContextTest` 检查 Spring 自动配置装配。

注意：项目当前 `.gitignore` 忽略整个 `src/test/`，新增测试文件保存在本地，但默认不会出现在 Git 待提交列表中。

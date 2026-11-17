# Plan 计划持久化：Session 与 Store 协作说明

本文说明 CorpTripAgent 当前基于 AgentScope 1.0.12 的 Plan 计划持久化实现，包括 `Session`、`PlanStorage`、`PlanNotebook`、`PersistSessionHook` 和 `PersistPlanNoteHook` 之间的职责边界与协作时序。

## 1. 核心结论

Plan 模式存在两类不同状态：

| 状态 | 当前项目的承载方式 | 作用 |
| --- | --- | --- |
| 当前正在执行的计划 | AgentScope `Session` 中的 `PlanNotebookState.currentPlan` | 新请求创建新的 Agent 后，继续执行上一次尚未结束的计划 |
| 已完成、已放弃或被恢复流程打断的历史计划 | `PlanNotebook` 的 `PlanStorage` | 查询历史计划，按计划 ID 恢复历史计划 |

两者不是同一个存储层：

```text
Session      -> 当前计划快照
PlanStorage  -> 历史计划归档
```

`PersistPlanNoteHook` 负责在 Agent 请求生命周期中读写当前计划的 Session 快照；`PlanStorage` 由 AgentScope 的 `PlanNotebook` 在归档、查询和恢复历史计划时调用。

## 2. 当前项目的实际配置

行程规划 Agent 位于 `ItineraryPlanningAgent`，当前创建方式为：

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .needUserConfirm(false)
        .build();
```

没有调用 `.storage(...)`，所以 AgentScope 使用默认的 `InMemoryPlanStorage`。因此：

- 当前计划通过 `PersistPlanNoteHook` 保存到项目配置的 AgentScope MySQL `Session`。
- 历史计划目前只保存到当前 `PlanNotebook` 实例的内存存储。
- 行程规划 Agent 使用 prototype 作用域，每次请求会创建新的 Agent 和新的 `PlanNotebook`，默认历史存储不能可靠地跨请求或跨重启保留。
- 项目中已经提供 `MysqlPlanStorage` 和 `RedisPlanStorage`，但当前尚未注入行程规划 Agent，因此运行时不会自动写入这两个 Store。

行程规划 Agent 还显式配置了：

```java
.statePersistence(StatePersistence.builder()
        .planNotebookManaged(false)
        .build())
```

这表示由 `ReActAgent` 的通用状态持久化逻辑管理 Memory、Toolkit 等状态，但不再自动管理 PlanNotebook。当前计划的加载和保存由 `PersistPlanNoteHook` 单独负责，避免与通用 `PersistSessionHook` 重复读写。

## 3. Session 保存什么

`PlanNotebook.saveTo(session, sessionKey)` 保存的是一个 `PlanNotebookState`：

```java
session.save(
        sessionKey,
        keyPrefix + "_state",
        new PlanNotebookState(currentPlan)
);
```

当前项目的 Session key 使用：

```text
CorpTripSessionKey(sessionId, agentName)
```

因此同一个业务会话下，不同 Agent 的计划状态相互隔离。计划状态字段默认类似：

```text
planNotebook_state
```

Session 保存的是某一时刻的当前计划快照，不是历史计划列表，也不是每一次计划修改的操作日志。

当当前计划已经完成时，AgentScope 会把 `currentPlan` 设置为 `null`。之后 Hook 保存：

```java
new PlanNotebookState(null)
```

这会覆盖之前的当前计划快照，表示当前会话已经没有正在执行的计划，避免下一次请求误恢复旧的活动计划。它通常不会删除整个 Session 记录。

## 4. PlanStorage 保存什么

AgentScope `PlanStorage` 接口提供三个操作：

```java
Mono<Void> addPlan(Plan plan);
Mono<Plan> getPlan(String planId);
Mono<List<Plan>> getPlans();
```

它主要用于计划归档和历史计划操作：

| AgentScope 操作 | PlanStorage 调用 | 结果 |
| --- | --- | --- |
| `finish_plan` | `addPlan(currentPlan)` | 保存已完成或已放弃的计划 |
| `view_historical_plans` | `getPlans()` | 查询历史计划摘要 |
| `recover_historical_plan` | `getPlan(planId)` | 按 ID 读取历史计划并设为当前计划 |

`PlanStorage` 不负责保存每一轮的当前计划，也不会自动替代 Session。计划执行过程中，子任务状态变化首先发生在内存中的 `currentPlan`，之后由 Hook 把当前快照保存到 Session。

## 5. 当前项目的 Hook 协作

### 5.1 `PersistSessionHook`

`PersistSessionHook` 是通用 Agent 状态 Hook，处理所有接入它的 Agent：

- 在 `PreCallEvent` 中调用 `agent.loadIfExists(session, sessionKey)`。
- 在其他非推理分片事件中调用 `agent.saveTo(session, sessionKey)`。
- 负责 AgentScope 通用状态，例如 Memory、Toolkit 状态等。
- 对行程规划 Agent 来说，因为 `planNotebookManaged(false)`，不会再通过 `ReActAgent` 自动加载或保存 PlanNotebook。

### 5.2 `PersistPlanNoteHook`

`PersistPlanNoteHook` 专门处理计划 Agent：

- 只处理 `event.getAgent()` 是 `ReActAgent` 且 `getPlanNotebook()` 不为空的 Agent。
- 没有有效 `sessionId` 时直接跳过，避免使用错误的 Session key。
- `PreCallEvent` 调用 `planNotebook.loadIfExists(session, sessionKey)`，恢复上一次的当前计划。
- 除 `ReasoningChunkEvent` 外，在生命周期事件中调用 `planNotebook.saveTo(session, sessionKey)`。
- 使用 `sessionId + agentName` 组成隔离 key。

该 Hook 当前只挂载到行程规划 Agent。其他 Agent 即使复用这个类，也会因为没有 PlanNotebook 而直接透传事件。

### 5.3 中断保存

用户中断 Agent 时，可能来不及等待正常 Hook 事件。`AgentRegistry.interruptLocally` 会：

1. 调用 Agent 的 `interrupt()`。
2. 保存 Agent 的通用 Session 状态。
3. 如果 Agent 挂载了 PlanNotebook，再显式调用 `planNotebook.saveTo(session, sessionKey)`。

这样可以尽量保留中断发生时的当前计划，下一次请求可以继续恢复。

## 6. 完整协作时间线

### 6.1 第一次请求

```text
用户发送行程规划请求
        |
        v
创建 prototype 行程规划 Agent
        |
        v
PreCallEvent
        |
        +--> PersistSessionHook：加载通用 Agent 状态
        |
        +--> PersistPlanNoteHook：查询 Session 中的 PlanNotebookState
                              未命中则 currentPlan 保持 null
        |
        v
AgentScope 推理
        |
        v
模型调用 create_plan
        |
        v
PlanNotebook 创建 currentPlan
        |
        +--> changeHook：ProgressNotificationHook 推送计划进度
        |
        v
模型继续执行子任务和业务工具
        |
        v
生命周期事件触发 PersistPlanNoteHook
        |
        v
Session 保存当前计划快照
```

### 6.2 后续请求恢复当前计划

```text
用户继续发送请求
        |
        v
创建新的 prototype 行程规划 Agent 和 PlanNotebook
        |
        v
PreCallEvent
        |
        v
PersistPlanNoteHook.loadIfExists(...)
        |
        v
Session 返回 PlanNotebookState.currentPlan
        |
        v
新的 PlanNotebook.currentPlan 恢复
        |
        v
Agent 基于恢复后的计划继续推理和执行
```

### 6.3 计划完成

```text
模型调用 finish_plan("done", outcome)
        |
        v
PlanNotebook.finishPlan()
        |
        +--> PlanStorage.addPlan(currentPlan)
        |       保存已完成计划的历史快照
        |
        +--> currentPlan = null
                清空当前活动计划
        |
        +--> changeHook：推送 currentPlan=null 的计划更新
        |
        v
PersistPlanNoteHook 后续保存
        |
        v
Session 保存 PlanNotebookState(null)
```

因此在历史 Store 已正确接入的前提下，完成后的数据状态应当是：

```text
Session：currentPlan = null
PlanStorage：保留已完成计划
```

### 6.4 查询和恢复历史计划

```text
模型调用 view_historical_plans
        |
        v
PlanStorage.getPlans()
        |
        v
返回历史计划摘要

模型选择某个 planId 后调用 recover_historical_plan
        |
        v
PlanStorage.getPlan(planId)
        |
        +--> 如果当前有未结束计划：先将当前计划标记为 abandoned 并写回 Store
        |
        +--> 将历史计划设为 currentPlan
        |
        +--> 触发计划变化通知
        |
        v
后续 Hook 事件将恢复后的 currentPlan 保存到 Session
```

## 7. MySQL 和 Redis Store 的当前状态

项目已经实现：

| Bean | 存储位置 | 当前是否被行程规划 Agent 使用 |
| --- | --- | --- |
| `mysqlPlanStorage` | `agentscope_plans` 表 | 否 |
| `redisPlanStorage` | Redis `agentscope:plans` Hash | 否 |
| AgentScope 默认 `InMemoryPlanStorage` | 当前 JVM 内存 | 是 |

要启用持久化历史 Store，需要在创建 `PlanNotebook` 时显式传入：

```java
PlanNotebook.builder()
        .storage(planStorage)
        .needUserConfirm(false)
        .build();
```

但当前 MySQL 和 Redis 实现的存储键主要使用计划 ID，`getPlans()` 会读取该实现范围内的全部计划。它们目前没有按用户或会话隔离，因此不能直接用于多用户生产场景，否则可能导致一个用户查询或恢复另一个用户的历史计划。

正式接入前应至少增加：

1. 用户 ID 或会话 ID 作为存储隔离维度。
2. 历史列表查询只返回当前用户可访问的计划。
3. 恢复操作校验计划归属，不能只凭 `planId` 读取。
4. MySQL 查询条件、Redis key/hash 结构和计划快照字段同步调整。

## 8. 当前实现的边界

- `Session` 负责当前计划恢复，不能替代历史计划 Store。
- `PlanStorage` 负责历史计划，不能替代请求开始时的当前计划加载。
- `PersistPlanNoteHook` 保存的是当前快照，不是完整变更历史。
- 当前默认 `InMemoryPlanStorage` 不适合跨请求、跨重启的历史计划恢复。
- `finish_plan` 只有在调用链成功完成并执行 `addPlan` 后，历史计划才会进入 Store。
- 当前计划在完成后写入 Session 的 `null` 是必要的清理动作，用于防止恢复旧计划。
- `addChangeHook` 负责计划变化通知，不等同于持久化事务，也不保证与 Session 或 Store 组成同一个数据库事务。

## 9. 一句话总结

当前项目采用“Session 保存当前计划、PlanStorage 保存历史计划、PersistPlanNoteHook 负责当前计划跨请求恢复、PlanNotebook 负责历史计划归档和恢复”的分工。行程规划 Agent 目前已经接入 Session 侧的当前计划持久化，但仍使用默认内存历史 Store；要实现安全可靠的跨用户历史计划查询和恢复，还需要接入按用户或会话隔离的 MySQL 或 Redis `PlanStorage`。

# ChatAgentPipeline 快速调度子智能体说明

## 1. 结论

项目在 `ChatAgentPipeline` 中实现了一条基于会话活跃 Agent 状态的快速调度分支。当用户本轮输入是精确匹配的继续/确认信号，并且当前会话保存了活跃子智能体名称时，Pipeline 会直接取得该子智能体并调用它，跳过本轮的分层意图识别和 `MasterAgent`。

这条分支主要解决多轮任务中的短回复，例如子智能体追问“是否确认提交？”后用户回答“确认”。它不是面向任意新问题的通用意图直达路由：普通业务问题仍由意图识别和 MasterAgent 编排。

## 2. 实现前：每轮完整编排

快速分支引入前，`ChatAgentPipeline.execute` 对每一轮消息都按以下顺序执行：

1. 校验输入，创建或读取会话，读取历史消息并保存本轮用户消息。
2. 执行 `IntentRecognizer.recognize(question, history)`。识别器可能经过 L0/L1/L2、历史查询改写和 L3 模型兜底。
3. 通过 SSE 推送意图识别进度和识别结果。
4. 创建 `MasterAgent`，把意图和改写结果作为路由参考消息传入。
5. 调用 `MasterAgent.stream(...)`，由 MasterAgent 决定回答、调用工具或委派子智能体。

因此，即使新一轮只是对上一轮追问的“好”“继续”或“确认”，也要先跑意图识别，再进入 MasterAgent。短确认往往缺少单独语义，意图识别可能无法还原它所确认的业务对象；完整编排还会增加一次识别链路的耗时和模型/向量检索开销。

## 3. 实现后：命中时直达活跃子智能体

当前 `ChatAgentPipeline.execute` 在启动 Agent 流程时先读取会话保存的 `activeAgent`，再判断本轮文本是否精确命中继续信号集合。两个条件同时成立时，流程如下：

1. 读取 `ActiveAgentSessionStore` 中与会话关联的活跃 Agent 名称。
2. 用 `isContinue(question)` 对输入做小写化，然后与固定词集合逐项做完整相等匹配；不是包含匹配。
3. 将 Agent 名称转换为 Spring Bean 名称，例如 `BookingAgent` 转为 `bookingAgent`。
4. 通过 `SpringContextUtil.getBean(..., ReActAgent.class)` 获取目标子智能体，直接调用其 `stream(...)`。
5. 沿用当前会话 ID 和 AgentScope Session，因此子智能体可以读取该会话已有的上下文/状态；事件仍通过 Pipeline 的 SSE 处理、持久化和错误收尾链路。

命中时不发送本轮意图识别开始/结束事件，也不创建 MasterAgent。若没有保存的活跃 Agent，或输入不是集合中的精确短语，则继续走原有“意图识别 + MasterAgent”流程。

目前 `ChatAgentPipeline.ALL` 包含“确定、确认、提交、继续、是的、好的、对、好、修改、补充、不对、取消、重新、再”以及 `ok`、`yes`、`confirm`、`continue` 等英文词和部分固定扩展短语。集合是精确白名单，输入“好的，继续”不会因为包含关键词而命中。

## 4. 活跃 Agent 如何保存

`ActiveAgentPersistenceHook` 在 AgentScope 的 `PreReasoningEvent` 中读取 Reactor Context 携带的 `AgentRuntimeContext`，取出会话 ID 和当前 Agent 名称。若 Agent 名称不是 `MasterAgent`，Hook 就调用 `ActiveAgentSessionStore.setActiveAgent(...)` 保存状态。

`ActiveAgentSessionStore` 使用 AgentScope `Session` 持久化状态，Session Key 为 `sessionId + ":router"`，field 为 `activeAgent`，值是 `ActiveAgentState(agentName)`。路由状态因此与 Agent 自身的 memory key 隔离，并可通过项目配置的 Session 后端跨请求读取。`MasterAgent` 自身不会覆盖这个值，子智能体推理时会把活跃目标更新为当前子智能体。

这个设计记录的是“最近进入推理的非 Master Agent”，不代表业务任务已成功，也不表示任何后续消息都应该继续调用它。当前通过严格的短语白名单限制快捷分支。

## 5. 例子

### 例一：确认预订信息

假设上一轮 `BookingAgent` [已经向用户展示预订信]()息，并等待用户确认；Hook 已将 `BookingAgent` 写入当前会话的 `activeAgent`。

用户回复：

```text
确认
```

当前处理：

```text
ChatAgentPipeline
  -> activeAgent = BookingAgent
  -> "确认" 精确命中 ALL
  -> 获取 bookingAgent
  -> bookingAgent.stream(...)
```

本轮跳过意图识别和 MasterAgent。子 Agent 仍须遵守自己的工具和确认校验规则；快速调度只选择执行入口，不会代替业务层确认或授权。

### 例二：输入是新问题而不是继续信号

假设当前活跃 Agent 是 `GeneralInfoAgent`，用户输入：

```text
查一下杭州明天的天气
```

此文本不与 `ALL` 中任何元素完整相等，因此走完整的意图识别和 MasterAgent 编排。即便能够通过规则判断为天气查询，当前代码也没有在这一分支中把该新问题直接路由给 `GeneralInfoAgent`。

### 例三：没有活跃子智能体状态

用户在新会话直接输入“确认”，由于没有 `activeAgent`，也会走意图识别和 MasterAgent，而不会盲目调用某个子智能体。

## 6. 前后对比

| 场景 | 快速分支之前 | 当前实现 |
| --- | --- | --- |
| 子 Agent 追问后的精确短确认，且会话有活跃 Agent | 意图识别 -> MasterAgent -> 再判断目标 | 直接调用已记录的子 Agent |
| 短确认但没有活跃 Agent | 意图识别 -> MasterAgent | 意图识别 -> MasterAgent |
| 普通业务新问题 | 意图识别 -> MasterAgent | 意图识别 -> MasterAgent |
| 多意图请求 | 意图识别 -> MasterAgent 编排 | 意图识别 -> MasterAgent 编排 |

收益集中在连续对话中的短确认轮次：少走一段识别链路和一次 MasterAgent 决策。目标 Agent 仍会进行自己的模型推理和工具调用，因此这不会消除子 Agent 执行本身的耗时。

## 7. 其他可选实现方案

### 方案 A：高置信单意图直接路由

在意图识别完成后，若结果是单意图、置信度/规则强度达到阈值、目标 Agent 在白名单内，就直接调用目标子 Agent；多意图、未知、低置信或有依赖顺序的请求仍走 MasterAgent。项目现有 L1 规则和识别结果已包含目标 Agent 信息，可用于构建这条分支。

优点是比短语白名单覆盖更多清晰的新问题，并可复用现有分层识别；代价是仍需先运行识别器，节省的是 MasterAgent 决策步骤。必须保留多意图和未知意图回退，否则会丢失组合任务。

### 方案 B：轻量级路由器一次分类

使用规则分类器或低成本小模型，仅输出受约束的 `targetAgent`、`confidence` 和 `isMultiIntent`，然后按阈值直达；不确定时再升级到现有完整识别与 MasterAgent。适合规则难以覆盖但单意图比例较高的请求。

优点是可降低完整识别的平均开销；缺点是增加一次路由模型调用和分类错误风险。路由输出必须限制在服务端 Agent 白名单内，不能让模型返回任意 Bean 名称。

### 方案 C：显式任务状态/待确认状态路由

由业务或编排层持久化当前任务阶段、等待的确认类型、目标 Agent、关联方案/订单版本及过期时间。只有收到与待确认状态匹配的答复才恢复目标 Agent；状态不匹配、过期或任务完成时回退到完整编排。

与只记最近活跃 Agent 相比，这种方式更适合预订、申请提交等有副作用的流程，可避免“确认”被错误用于另一个任务。不过需要设计状态机、并发控制、超时清理和幂等校验。

### 方案 D：统一确定性路由表

将意图到 Agent 的映射从 `IntentType`、Prompt 和 Spring Bean 名称之间统一为服务端注册表，例如 `IntentType -> AgentId -> Agent 实例工厂`。Pipeline 对单意图结果直接查表，对多意图和跨 Agent 组合任务交给 MasterAgent。

优点是路由白名单、观测和维护更集中；需要避免将配置字符串直接当 Bean 名称，也要处理每个 Agent 的会话隔离、权限和运行时上下文。

## 8. 实施建议与边界

1. 保持当前短确认快速路径作为低风险优化，并补充“精确匹配、没有活跃状态、未知 Agent、状态过期”等验证场景。
2. 若要让“查天气”“查申请”等清晰单意图问题也绕过 MasterAgent，优先评估方案 A：复用现有识别结果直达，未知、多意图和低置信统一回退。此举仍会执行意图识别。
3. 对会触发预订、取消、申请提交等副作用的快捷路由，使用方案 C 的明确任务阶段和服务端确认凭证；不能仅凭“确认”或最近活跃 Agent 直接认定操作已获授权。
4. 记录命中路径、目标 Agent、回退原因、识别耗时、Agent 首 Token 时长和最终结果，比较命中前后的端到端延迟与错误率。
5. 保证 `AgentRuntimeContext` 的 userId、sessionId、traceId 在直接调度分支和全量分支一致；目标 Agent 应继续使用对应会话的持久化 Session 和现有权限校验工具。

## 9. 关键代码位置

- 快速分支与 `isContinue`：`src/main/java/cn/lwccxy/trip/business/chat/ChatAgentPipeline.java`
- 路由状态 Session 读写：`src/main/java/cn/lwccxy/trip/agent/store/activeagent/ActiveAgentSessionStore.java`
- 活跃 Agent 持久化 Hook：`src/main/java/cn/lwccxy/trip/agent/hooks/ActiveAgentPersistenceHook.java`
- Hook Bean 配置：`src/main/java/cn/lwccxy/trip/agent/config/CorpTripAgentHookConfig.java`
- Agent 意图到目标名称映射：`src/main/java/cn/lwccxy/trip/agent/enums/IntentType.java`


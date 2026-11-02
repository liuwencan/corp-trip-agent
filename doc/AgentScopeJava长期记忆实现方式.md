# AgentScope Java 1.0.12 长期记忆实现方式

## 背景与范围

本文记录 AgentScope Java 1.0.12 中，`ReActAgent.Builder` 配置长期记忆的两种控制方式：静态控制（`STATIC_CONTROL`）和智能体控制（`AGENT_CONTROL`），并说明两种方式的调用流程与适用场景。

这里的长期记忆由 `LongTermMemory` 实现读写接口，负责跨轮次持久化和检索信息；它与 Agent 的短期 `Memory` 不同。短期 `Memory` 保存当前 Agent 的对话上下文，具体长期记忆实现负责定义信息如何存取、检索和隔离。

## 配置入口

创建 Agent 时，先构造一个 `LongTermMemory` 实现，再在 Builder 上设置记忆实现和控制模式：

```java
.longTermMemory(longTermMemory)
.longTermMemoryMode(mode)
```

其中 `mode` 可设置为 `LongTermMemoryMode.STATIC_CONTROL` 或 `LongTermMemoryMode.AGENT_CONTROL`。控制方式决定何时调用长期记忆，而具体的存储和检索后端由 `LongTermMemory` 实现负责。构造长期记忆实现时，应根据应用的身份体系和数据隔离要求设置用户或租户范围，以及后端连接参数。

## 方式一：静态控制

静态控制对应 `LongTermMemoryMode.STATIC_CONTROL`。在 Agent 构建时，`Builder.build()` 检查已配置 `LongTermMemory` 后调用 `configureLongTermMemory()`；静态控制分支会创建 `StaticLongTermMemoryHook`，并将 Hook 加入 Agent 的 Hook 集合。该模式不额外向 Toolkit 注册长期记忆工具。

每次 Agent 调用时，Hook 自动执行以下流程：

1. 在 `PreCallEvent` 中查找输入消息列表里最后一条用户消息。
2. 将该消息传给 `LongTermMemory.retrieve()`。若检索结果非空，则包装成名为 `long_term_memory` 的用户消息，追加到本次模型输入中；没有结果时保持原输入不变。
3. 在 `PostCallEvent` 中取得 Agent 短期 `Memory` 的消息列表，并调用 `LongTermMemory.record()` 写入长期记忆。
4. 若检索或记录发生错误，Hook 记录警告并继续对话，不让长期记忆故障直接中断 Agent 调用。

`longTermMemoryAsyncRecord(true)` 可让静态模式的记录在后台调度；默认值为 `false`，此时记录操作处于本次调用链中。该选项只改变记录执行时机，不改变检索仍发生在调用前这一点。

### 适用场景

- 每轮对话都应该自动尝试召回用户偏好，并在对话后自动沉淀信息。
- 记忆策略统一、规则明确，不需要模型判断是否要检索或记录。
- 希望将记忆操作从提示词和工具选择中移除，减少模型漏调工具的概率。

静态控制的代价是固定的调用节奏：每次调用都会尝试检索，结束时会尝试记录当前短期消息。对话内容未必都适合沉淀时，需要在 `LongTermMemory` 后端或外围策略中控制筛选，避免低价值内容累积。

## 方式二：智能体控制

智能体控制对应 `LongTermMemoryMode.AGENT_CONTROL`。在 `Builder.build()` 的长期记忆配置分支中，AgentScope 将 `LongTermMemoryTools` 注册到 Agent 的 Toolkit；该模式不会安装 `StaticLongTermMemoryHook`。工具封装当前 `LongTermMemory` 的检索和记录接口，Agent 在 ReAct 循环中按需调用。

运行流程如下：

1. Agent 接收用户请求并结合系统提示词、当前对话和已注册工具进行推理。
2. 如果判断需要了解过去的偏好或约束，模型可调用长期记忆检索工具；工具用用户输入查询 `LongTermMemory.retrieve()`，再将结果返回给模型。
3. Agent 根据检索结果继续推理和回答。若用户提供了适合长期保留的新偏好或稳定信息，模型可调用记录工具，将相关消息交给 `LongTermMemory.record()`。
4. 如果模型判断无需检索或记录，则不会自动执行对应操作。

使用该模式时，系统提示词和工具描述应清楚说明哪些信息值得记忆、何时应先检索，以及哪些临时或敏感信息不应保存。工具是否被调用取决于模型判断和工具描述，提示词、工具定义和模型能力会影响执行质量。

### 适用场景

- 记忆读写应由任务语义决定，例如只在任务确实涉及用户长期偏好时检索。
- 记录前需要模型从对话中归纳稳定偏好，而非机械保存整段对话。
- 希望控制记忆服务调用次数，避免每轮无条件检索和记录。
- 可接受模型偶尔未检索或未记录，并能通过提示词、工具描述和验证逐步改善策略。

## 两种方式对比

| 维度 | 静态控制 `STATIC_CONTROL` | 智能体控制 `AGENT_CONTROL` |
| --- | --- | --- |
| 控制方 | AgentScope Hook | Agent 根据任务推理选择工具 |
| 检索时机 | 每次调用前自动尝试 | 模型决定是否检索及何时检索 |
| 记录时机 | 每次调用后自动尝试记录短期 Memory 消息 | 模型决定是否记录以及提交什么内容 |
| 构建时行为 | 添加 `StaticLongTermMemoryHook` | 向 Toolkit 注册 `LongTermMemoryTools` |
| 主要优势 | 执行稳定、策略集中、较少依赖模型遵循工具调用 | 调用有选择性、可归纳后记录、减少不必要读写 |
| 主要成本 | 可能产生无效检索或低价值记录 | 依赖模型判断，工具可能漏调 |

## `BOTH` 组合模式

AgentScope 1.0.12 的 `LongTermMemoryMode.BOTH` 会同时注册 `LongTermMemoryTools` 并添加 `StaticLongTermMemoryHook`。因此每次调用会有静态自动检索和记录，同时模型也拥有主动调用记忆工具的能力。若两条路径处理同一轮对话，可能出现重复检索或重复记录；只有明确需要自动兜底并允许额外调用开销时才适合启用。Builder 的默认长期记忆模式为 `BOTH`，应显式配置模式以避免依赖默认值造成行为不清晰。

## 通用实现注意事项

无论使用哪种控制模式，实现和运行时都应关注以下边界：

- 长期记忆的数据隔离标识必须来自可信的身份上下文；不能直接信任客户端任意传入的用户或租户标识。
- AgentScope Agent 的会话状态持久化与长期记忆承担不同职责：前者恢复会话上下文，后者跨对话存取持久信息。
- 静态模式写入的是短期 `Memory` 消息列表；应核对所用 `LongTermMemory` 实现如何筛选、归纳和去重这些消息。
- 智能体控制模式的工具调用不等同于确定性业务规则。涉及隐私、敏感信息或必须遵守的保存策略时，应在后端记忆实现中增加约束，不能只依赖提示词。
- 部署长期记忆后端时，应确认服务地址、可达性、鉴权配置和数据隔离策略符合运行环境要求。

## 源码依据

本文流程依据 AgentScope Java 1.0.12 的实现核对：

- `io.agentscope.core.ReActAgent.Builder#build`：仅在 `longTermMemory` 非空时配置长期记忆；Builder 默认模式为 `BOTH`。
- `io.agentscope.core.ReActAgent.Builder#configureLongTermMemory`：`AGENT_CONTROL` 注册 `LongTermMemoryTools`；`STATIC_CONTROL` 添加 `StaticLongTermMemoryHook`；`BOTH` 同时启用两者。
- `io.agentscope.core.memory.StaticLongTermMemoryHook`：`PreCallEvent` 检索最后一条用户消息并将结果追加到输入；`PostCallEvent` 记录 Agent 短期 Memory 消息。


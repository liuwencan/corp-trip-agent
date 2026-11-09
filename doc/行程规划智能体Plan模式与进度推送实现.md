# 行程规划智能体 Plan 模式与进度推送实现

## 1. 文档范围

本文基于 CorpTripAgent 当前代码，说明行程规划智能体的两条配合链路：

1. Plan 模式：行程规划 Agent 如何创建计划、执行顺序子任务、更新状态并结束计划。
2. 进度推送：计划变化、工具调用和模型文本如何通过 SSE 推送给前端。

主要涉及：

- src/main/java/cn/lwccxy/trip/agent/ItineraryPlanningAgent.java
- src/main/java/cn/lwccxy/trip/agent/MasterAgent.java
- src/main/java/cn/lwccxy/trip/agent/hooks/ProgressNotificationHook.java
- src/main/java/cn/lwccxy/trip/business/chat/ChatAgentPipeline.java
- src/main/java/cn/lwccxy/trip/business/chat/ChatController.java
- src/main/java/cn/lwccxy/trip/agent/sse/SseEmitterRegistry.java
- src/main/java/cn/lwccxy/trip/agent/sse/SseEmitterSender.java
- src/main/java/cn/lwccxy/trip/agent/enums/SseEventEnum.java
- src/main/resources/sysprompt/ItineraryPlanningAgentPrompt.md

项目使用 AgentScope Java 1.0.12。本文描述的是当前项目的实际接入方式，不是一个脱离项目的通用示例。

## 2. 先看整体关系

行程规划 Agent 不是直接由 Controller 调用，而是被 MasterAgent 注册成一个子 Agent 工具：

    ChatController
        |
        v
    ChatAgentPipeline.execute()
        |
        v
    MasterAgent.stream()
        |
        | 模型选择 ItineraryPlanningAgent 工具
        v
    ItineraryPlanningAgent
        |
        +-- PlanNotebook：管理计划和子任务
        +-- PlanNotebook changeHook：推送 plan_update
        +-- ProgressNotificationHook：推送工具和思考事件
        +-- PersistSessionHook：保存/加载 AgentScope Session
        +-- 普通业务工具、途牛 CLI、天气 MCP、长期记忆

进度推送有两种来源：

- 计划状态变化：由 PlanNotebook 的 changeHook 主动发送 plan_update。
- AgentScope 生命周期事件：由 ProgressNotificationHook 处理 PreActingEvent、PostActingEvent 和 ReasoningChunkEvent。

二者最后都写入本次会话对应的 SseEmitter。

## 3. 一次请求如何创建运行上下文

### 3.1 Controller 层

ChatController 暴露两个 SSE 接口：

- POST /chat/chat：启动新一轮对话。
- POST /chat/resume：提交用户对 ask_user 的回答并恢复执行。

两个方法都返回 Spring MVC 的 SseEmitter，响应类型是 text/event-stream。

### 3.2 ChatAgentPipeline.execute

新请求进入 ChatAgentPipeline.execute 后，主要步骤如下：

1. 校验问题非空且长度不超过限制。
2. 创建或读取会话，得到最终 conversationId。
3. 把用户消息保存到业务会话表。
4. 创建 SseEmitter，并用 conversationId 注册到 SseEmitterRegistry。
5. 创建 AgentRuntimeContext，写入 userId、sessionId 和 traceId。
6. 把 AgentRuntimeContext 放入 ThreadLocal，随后创建 prototype MasterAgent。
7. Agent 创建完成后清理 ThreadLocal，防止线程池复用造成上下文泄漏。
8. 组装用户消息和意图识别结果。
9. 调用 masterAgent.stream(inputMsgs, StreamOptions.defaults())。
10. 在订阅回调里处理主 Agent 的推理增量和最终结果。

sessionId 在本项目中使用 conversationId 的字符串形式。它同时用于：

- 查找 SSE 连接。
- 构造 AgentScope SessionKey。
- 在 Reactor Context 中定位本次请求的用户和会话上下文。
- 给行程规划 Agent 的计划 Hook 捕获。

代码中的关键连接关系可以概括为：

    String sessionId = activeConversationId.toString();
    sseEmitterRegistry.register(sessionId, emitter);

    AgentRuntimeContext context = AgentRuntimeContext.build()
        .sessionId(sessionId)
        .userId(userId.toString())
        .traceId(traceId)
        .builder();

    masterAgent.stream(..., Context.of("agentRuntimeContext", context));

## 4. MasterAgent 如何调用行程规划 Agent

MasterAgent 也是 prototype 作用域。每次构建时，它从 Spring 容器取得本次会话专属的三个子 Agent：

- GeneralInfoAgent
- OrderManagementAgent
- ItineraryPlanningAgent

行程规划 Agent 被包装为一个 SubAgentTool：

    SubAgentConfig config = SubAgentConfig.builder()
        .toolName("ItineraryPlanningAgent")
        .description("负责根据出差目标和用户约束规划交通、住宿及日程候选方案...")
        .forwardEvents(false)
        .build();

    toolkit.registerAgentTool(
        new SubAgentTool(() -> itineraryPlanningAgent, config)
    );

### 4.1 forwardEvents(false) 的实际含义

这里没有把行程规划 Agent 的 AgentScope 事件转发给 MasterAgent。也就是说：

- MasterAgent 的 Hook 不会收到行程规划 Agent 内部每一次工具调用。
- 主 Agent 的 stream 只会看到子 Agent 工具整体调用的边界。
- 行程规划 Agent 自己必须拥有 ProgressNotificationHook，并直接使用会话 SSE 连接发送内部进度。

这正是当前设计能够展示子 Agent 内部实时进度的原因：事件不走父 Agent 的事件转发，而是走共享的 sessionId -> SseEmitter 注册表。

## 5. 行程规划 Agent 的 Plan 模式装配

### 5.1 创建 Toolkit 和业务工具

ItineraryPlanningAgent.itineraryPlanningAgent() 每次创建新 Agent 时都会：

1. 从 AgentRuntimeContextHolder 读取当前会话上下文。
2. 创建 ToolExecutionContext，并注册本次 AgentRuntimeContext。
3. 创建 Toolkit。
4. 注册目的地实时资讯、用户供应商 API Key 状态、出差行程单读取等工具。
5. 按 YAML 白名单注册天气 MCP 工具。
6. 创建 SkillBox，加载 tuniu-cli Skill 和受限 Shell 工具。
7. 创建长期记忆实现。

这一层的职责是准备“计划执行时可以使用的真实工具”。PlanNotebook 只负责计划状态，不负责天气、航班、酒店或行程单查询。

### 5.2 创建 PlanNotebook

当前配置是：

    PlanNotebook planNotebook = PlanNotebook.builder()
        .needUserConfirm(false)
        .build();

needUserConfirm(false) 表示默认计划提示不会要求用户额外确认后才开始执行。它不表示跳过 Plan 模式，模型仍然会看到计划工具和计划提示，仍然需要按提示创建、推进和结束计划。

没有配置 maxSubtasks，因此计划子任务数量默认不限制。没有注入 storage，因此历史计划使用 AgentScope 默认的 InMemoryPlanStorage。

### 5.3 绑定计划变化 Hook

创建 Notebook 后，项目按会话注册 Hook：

    if (agentRuntimeContext.getSessionId() != null) {
        planNotebook.addChangeHook(
            "plan",
            (notebook, plan) ->
                progressNotificationHook.sendPlanUpdate(
                    agentRuntimeContext.getSessionId(), plan
                )
        );
    }

这里有三个关键点：

1. Hook 捕获的是创建 Agent 时的 sessionId，不从共享字段读取其他请求的会话。
2. Hook 接收的是计划变化后的 Plan；Plan 为 null 通常表示当前计划已完成或已清空。
3. Hook 不负责执行计划，只负责把当前状态转换成前端可展示的数据并发送。

### 5.4 把 Notebook 交给 ReActAgent

最终 Agent builder 中设置：

    .planNotebook(planNotebook)
    .hooks(List.of(
        persistSessionHook,
        tuniuApiKeyShellHook,
        tuniuSearchResultHook,
        progressNotificationHook,
        modelUsageBillingHook,
        longTermMemoryRecordHook
    ))
    .build();

AgentScope 1.0.12 在构建 ReActAgent 时会自动完成两件事：

- 把 PlanNotebook 注册为 Toolkit 工具对象，暴露 create_plan、update_subtask_state、finish_subtask 等计划工具。
- 注册计划 Hook，在每次 PreReasoningEvent 前调用 Notebook 的 getCurrentHint()，把最新计划提示追加到模型输入。

因此，项目没有手动注册计划工具，也没有在 ChatAgentPipeline 中手工驱动子任务。

## 6. 行程规划提示词如何约束计划过程

行程规划系统提示词位于：

    src/main/resources/sysprompt/ItineraryPlanningAgentPrompt.md

它对业务流程做了额外约束，主要包括：

1. 先查询用户出差申请单和审批记录，审批通过后才进行具体规划。
2. 用户明确提出规划诉求时，先检索长期记忆中的出行偏好。
3. 查询出发地、目的地和日期范围的天气。
4. 查询目的地新闻和实时资讯。
5. 首次使用途牛 CLI 前检查当前用户的 TUNIU API Key。
6. 凭证通过后加载 tuniu-cli Skill，再使用真实 CLI 查询。
7. 结合真实搜索结果、用户偏好和天气进行候选方案比较。
8. 只生成交通、住宿和日程候选方案，不执行预订或支付。
9. 根据候选结果设计行程方案，并交给审核子智能体检查。

这里的业务提示词和 PlanNotebook 提示是两层规则：

- 系统提示词规定“行程规划业务要做什么”。
- PlanNotebook 动态提示规定“当前计划处于哪一步，下一步应该怎样推进”。

模型每轮推理会同时接收业务系统提示和当前计划提示。计划提示会随子任务状态变化而重新生成。

## 7. 计划状态推进流程

PlanNotebook 的实际状态推进由模型调用计划工具完成，项目不在 Java 代码中编排固定流程。

典型流程如下：

    1. 模型判断任务复杂，需要规划。
    2. 调用 create_plan 创建计划和顺序子任务。
    3. PlanNotebook 设置 currentPlan，并触发 changeHook。
    4. 前端收到第一次 plan_update。
    5. 下一轮推理读取最新计划提示。
    6. 模型调用 update_subtask_state(index, "in_progress")。
    7. 计划 Hook 再次触发，前端看到当前任务进入进行中。
    8. 模型调用普通工具查询行程单、天气、资讯或途牛结果。
    9. 模型调用 finish_subtask(index, outcome)。
    10. PlanNotebook 将当前任务设为 done，并自动激活下一个任务。
    11. changeHook 再次推送最新任务列表。
    12. 所有任务完成后，模型调用 finish_plan("done", outcome)。
    13. PlanNotebook 保存历史计划、清空 currentPlan。
    14. changeHook 收到 null，推送空任务列表。

如果模型需要调整计划，会调用 revise_current_plan。新增、修改、删除子任务都会触发同一个 changeHook，因此前端任务列表可以同步变化。

如果用户中途要求补充信息，模型可以调用 ask_user。该工具会暂停 Agent，不会结束会话业务记录；用户通过 /chat/resume 提交回答后，Pipeline 构造 ask_user 的 ToolResultBlock，继续调用 MasterAgent。

## 8. plan_update 的生成过程

### 8.1 触发点

AgentScope PlanNotebook 在以下变化后会触发 changeHook：

- 创建计划。
- 修改计划名称、描述或预期结果。
- 新增、修改或删除子任务。
- 更新子任务状态。
- 完成子任务。
- 完成或放弃计划。
- 恢复历史计划。

当前项目只注册了一个名为 plan 的 Hook，执行方法是 ProgressNotificationHook.sendPlanUpdate。

### 8.2 连接查找

sendPlanUpdate 首先根据 sessionId 查找 SSE 连接：

    SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);

如果 sessionId 为空或当前连接不存在，方法直接返回。计划状态更新不会因为前端未连接而阻塞 Agent 执行。

### 8.3 事件数据结构

计划推送使用 SseEventEnum.PLAN_UPDATE，对应 SSE 事件名 plan_update。data 是一个 Map，结构为：

    {
      "type": "plan_update",
      "agentName": "ItineraryPlanAgent",
      "planName": "行程规划",
      "tasks": [
        { "idx": 0, "name": "查询审批状态", "state": "done" },
        { "idx": 1, "name": "查询天气", "state": "in_progress" },
        { "idx": 2, "name": "整理候选方案", "state": "todo" }
      ]
    }

字段含义：

- type：固定为 plan_update。
- agentName：固定展示名 ItineraryPlanAgent。
- planName：当前计划名称；计划清空时为空字符串。
- tasks：任务数组；计划清空时为空数组。
- idx：任务在当前 Plan.subtasks 列表中的索引。
- name：子任务名称。
- state：todo、in_progress、done 或 abandoned。

前端必须以 idx 和当前最新 tasks 为准。计划被增删改后，旧索引可能失效。

### 8.4 计划完成时的特殊推送

finish_plan 会让 PlanNotebook 的 currentPlan 变为 null。changeHook 仍然会执行，因此 sendPlanUpdate 会发送：

    {
      "type": "plan_update",
      "agentName": "ItineraryPlanAgent",
      "planName": "",
      "tasks": []
    }

前端可以把这个事件理解为“当前计划已结束或不存在”，清空计划面板。

## 9. 普通 Agent 进度事件如何推送

计划推送只反映任务列表；工具执行和模型输出由 ProgressNotificationHook 负责。

### 9.1 Hook 获取会话连接

ProgressNotificationHook 是 Spring 单例，但不保存用户、sessionId 或工具调用状态。每次 onEvent 都从 Reactor Context 获取：

    AgentRuntimeContext runtimeContext =
        contextView.getOrDefault("agentRuntimeContext", null);

然后用 runtimeContext.getSessionId() 查询当前 SseEmitter。这样同一个共享 Hook 可以安全处理并发会话。

如果没有上下文或没有连接，Hook 返回原始事件，不影响 AgentScope 继续运行。

### 9.2 工具开始：tool_start

收到 PreActingEvent 时：

1. 读取 ToolUseBlock。
2. 创建 AgentProgressVO。
3. 设置事件 ID、Agent ID、Agent 展示名、时间戳和 running 状态。
4. 写入 toolCallId、toolName、tooldesc 和 input。
5. 发送 SSE 事件 tool_start。
6. 原始 AgentScope 事件继续向后执行。

toolCallId 是工具开始和工具结果之间的关联键，前端可以用它把同一次调用合并展示。

### 9.3 工具结束：tool_result

收到 PostActingEvent 时：

1. 读取 ToolResultBlock。
2. 复用原始 ToolUseBlock 的调用信息。
3. 从结果 ContentBlock 中提取所有 TextBlock。
4. 使用换行符拼接文本结果。
5. 根据 result.isSuspended() 设置状态：
   - suspended：工具暂停等待外部输入。
   - completed：工具调用阶段已返回。
6. 发送 tool_result SSE 事件。

completed 只代表工具执行阶段已返回，不等于业务操作一定成功。业务成功与否仍需看 output 内容或工具的错误信息。

### 9.4 模型文本增量：thinking

收到 ReasoningChunkEvent 时：

1. 读取增量 Msg。
2. 提取文本内容。
3. 空文本片段直接跳过。
4. 创建 AgentProgressVO。
5. 事件类型设置为 thinking。
6. tooldesc 固定为“智能体输出”。
7. output 写入当前增量文本。
8. messageId 使用 accumulated 消息 ID；没有 accumulated 时退回 chunk ID。
9. 发送 thinking SSE 事件。

ProgressNotificationHook 推送 thinking 是时间线展示；ChatAgentPipeline 另外会从主 Agent 的 EventType.REASONING 中发送 delta 事件，作为最终回答文本流。两者不要混为同一个协议字段。

## 10. SSE 连接生命周期

### 10.1 注册

ChatAgentPipeline.execute 和 resume 都会创建新的 SseEmitter，并按会话 ID注册：

    sseEmitterRegistry.register(conversationId.toString(), emitter);

SseEmitterRegistry 使用 ConcurrentHashMap 保存会话到连接的映射。

### 10.2 同一会话重复连接

如果同一个 sessionId 已有旧连接，register 会：

1. 用新连接替换 Map 中的旧连接。
2. 为新连接注册完成、超时和错误回调。
3. 尝试 complete 旧连接。

因此，同一个会话只保留一个当前 SSE 连接，后续进度都写入最新连接。

### 10.3 连接结束

连接在以下情况会被移除：

- 正常完成。
- 超时。
- 发送错误。
- 业务调用 unregister 或 unregisterAll。

unregister 使用 emitters.remove(sessionId, emitter)，只有回调中的连接仍是当前连接时才会删除，避免旧连接回调误删新连接。

### 10.4 发送失败

SseEmitterSender.send 统一捕获 IOException 和 IllegalStateException：

- 客户端断开时记录日志。
- 不把 SSE 写入失败重新抛回 Agent。
- 不因为前端断开而中断计划或工具执行。

这意味着进度推送是尽力而为的旁路能力，Agent 的业务执行不依赖前端是否在线。

## 11. 当前 SSE 事件分类

SseEventEnum 中与行程规划相关的事件包括：

| 事件 | 来源 | 前端用途 |
| --- | --- | --- |
| plan_update | PlanNotebook changeHook | 更新计划和子任务列表 |
| tool_start | ProgressNotificationHook / PreActingEvent | 显示工具开始执行 |
| tool_result | ProgressNotificationHook / PostActingEvent | 显示工具结果或暂停状态 |
| thinking | ProgressNotificationHook / ReasoningChunkEvent | 展示智能体思考增量 |
| delta | ChatAgentPipeline / 主 Agent REASONING | 拼接对话回答文本 |
| ask | ConfirmationHook / ask_user | 展示需要用户补充的信息 |
| complete | ChatAgentPipeline / 最终 Agent 结果 | 标记本轮对话完成 |
| error | ChatAgentPipeline 错误分支 | 标记对话失败 |

其中 plan_update 的 data 不是 AgentProgressVO，而是计划专用 Map；其他时间线工具和思考事件一般使用 AgentProgressVO。前端解析时应按 event name 分支处理，不应强行使用同一数据模型。

## 12. ask_user 暂停和恢复

行程规划 Agent 的系统提示要求在关键出行信息缺失时向用户澄清。它通过 ask_user 工具实现：

1. 模型调用 ask_user。
2. AskUserTool 抛出 ToolSuspendException。
3. AgentScope 将调用变成暂停结果。
4. ConfirmationHook 在 PostReasoningEvent 中识别 ask_user。
5. Hook 将 toolCallId 和 input 持久化为工具消息。
6. Hook 发送 ask SSE 事件。
7. Hook 调用 stopAgent，暂停当前 Agent。
8. ChatAgentPipeline 检测到包含 ask_user 的暂停结果，正常结束当前 SSE，不发送错误。
9. 前端提交答案到 POST /chat/resume。
10. Pipeline 保存用户回答，构造 ask_user 的 ToolResultBlock。
11. 重新创建 MasterAgent 和 SSE 连接，调用 masterAgent.stream(List.of(toolResultMsg))。
12. AgentScope 根据 Session 和待处理工具调用继续执行。

因此，ask_user 是“暂停并恢复”，不是一轮失败；计划状态和 Session 状态可以在恢复后继续使用。

## 13. Session 持久化与计划恢复

行程规划 Agent 注册了 PersistSessionHook。该 Hook 会：

- 在 PreCallEvent 中根据 sessionId 和 Agent 名称调用 agent.loadIfExists(...)。
- 在其他事件中调用 agent.saveTo(...)。
- 跳过 ReasoningChunkEvent，避免每个文本片段都触发 Session 写入。

ReActAgent 的状态持久化包含 AgentScope Session、Memory、Toolkit 状态和 PlanNotebook 状态。PlanNotebook 保存的状态 key 默认是 planNotebook_state，内容是当前 Plan 的快照。

需要区分两种数据：

- 当前执行中的计划：由 PlanNotebookState 随 Agent Session 保存。
- 已结束的历史计划：PlanNotebook 默认使用 InMemoryPlanStorage，当前项目没有注入数据库存储。

因此，当前计划可以随 Session 恢复，但历史计划不具备可靠的跨实例和跨重启持久性。由于行程规划 Agent 是 prototype 作用域，默认内存历史存储还可能随着新的 Agent 实例创建而重新开始。

## 14. 一次完整的前端时间线示例

一个正常的行程规划请求可能看到以下事件顺序：

    tool_start
      ItineraryPlanningAgent -> query_trip_orders

    tool_result
      查询出差申请单结果

    plan_update
      创建“杭州出差行程规划”
      任务 0 为 in_progress，后续任务为 todo

    tool_start
      ItineraryPlanningAgent -> retrieveFromMemory

    tool_result
      用户偏好结果

    plan_update
      任务 0 done，任务 1 in_progress

    tool_start
      ItineraryPlanningAgent -> execute_shell_command

    tool_result
      途牛航班或酒店查询结果

    thinking
      模型输出规划过程中的文本增量

    plan_update
      任务 1 done，任务 2 in_progress

    tool_start / tool_result
      继续查询天气、目的地资讯或行程单

    plan_update
      所有任务 done

    plan_update
      tasks=[]，表示 finish_plan 已清空当前计划

    delta
      主 Agent 面向用户的最终回答增量

    complete
      本轮对话完成

实际顺序取决于模型的工具选择、是否需要澄清和工具返回速度；PlanNotebook 不会预先发出固定数量的进度事件。

## 15. 当前实现的边界

1. Plan 模式是模型驱动的状态机，不是 Java 代码驱动的固定工作流。
2. 计划子任务按列表索引顺序推进，不支持依赖图、并行分支或条件节点。
3. plan_update 只推送任务名称和状态，不推送任务描述、预期结果和 outcome。
4. tool_result 的 completed 仅代表工具调用结束，不代表业务结果成功。
5. 子 Agent 事件不会由 MasterAgent 转发，依赖子 Agent 自己的 Hook 直接推送。
6. SSE 连接是单机会话连接；同一 sessionId 新建连接会替换旧连接。
7. SSE 发送失败不会阻塞 Agent，因此前端可能丢失中间事件，只能以最新状态或最终结果校正。
8. ProgressNotificationHook 不保存跨事件状态，因此调用关联依赖 AgentScope PostActingEvent 自带的 ToolUseBlock 和 toolCallId。
9. 计划 Hook 捕获 sessionId；不能把 sessionId 放进共享 Hook 字段，否则会产生并发串会话风险。
10. 当前历史计划存储是内存级别，不能作为可靠的审计或恢复数据源。

## 16. 建议的前端处理方式

前端建议按以下规则实现：

- 使用 SSE event name 区分 plan_update、tool_start、tool_result、thinking、delta、ask 和 complete。
- 收到 plan_update 时，整体替换当前计划任务列表，不做局部索引补丁。
- 用 toolCallId 关联同一次 tool_start 和 tool_result。
- 对 thinking 和 delta 分开渲染：前者是时间线过程信息，后者是最终回答正文。
- 收到 tool_result 且 status=suspended 时进入等待用户输入状态，不显示为失败。
- 收到 ask 时保存 toolCallId，提交 /chat/resume 时原样带回。
- 收到 plan_update 且 tasks 为空时清空当前计划面板。
- 收到 complete 后结束本轮回答；如果此前有丢失的 plan_update，以最终对话结果和下一次 Session 状态为准。
- 不把 plan_update 的 Map 当作 AgentProgressVO 反序列化，因为两者字段结构不同。

## 17. 总结

当前行程规划智能体的 Plan 模式由 AgentScope PlanNotebook 提供状态和工具，由 ItineraryPlanningAgent 的业务提示词规定规划规则，由模型主动调用计划工具推进任务。

进度推送则分成两条并行路径：

- PlanNotebook changeHook 把计划状态变化转换为 plan_update，直接发送任务列表。
- ProgressNotificationHook 把 AgentScope 工具和推理事件转换为 tool_start、tool_result、thinking，直接发送时间线事件。

ChatAgentPipeline 负责创建和注册 SSE、启动 MasterAgent 流、发送主 Agent 的 delta 和 complete，并在 ask_user 暂停时通过 /chat/resume 恢复。MasterAgent 关闭子 Agent 事件转发，所以行程规划子 Agent 必须使用同一个 sessionId 和 SseEmitterRegistry 直接推送内部事件。

最终形成的链路是：

    模型决定计划动作
        -> PlanNotebook 修改状态
        -> changeHook 发送 plan_update
        -> Progress Hook 发送工具/思考事件
        -> SseEmitterSender 写入当前会话连接
        -> 前端按事件名更新计划面板、时间线和回答正文

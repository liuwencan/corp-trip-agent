# AgentScope Java 计划模式实现原理

## 1. 阅读范围与结论

本文针对项目当前使用的 AgentScope Java 1.0.12，重点阅读以下类型：

- io.agentscope.core.plan.PlanNotebook
- io.agentscope.core.plan.model.Plan
- io.agentscope.core.plan.model.SubTask
- io.agentscope.core.plan.model.PlanState
- io.agentscope.core.plan.model.SubTaskState
- io.agentscope.core.plan.hint.DefaultPlanToHint
- io.agentscope.core.plan.storage.PlanStorage
- io.agentscope.core.plan.storage.InMemoryPlanStorage
- io.agentscope.core.ReActAgent.Builder 中的计划配置逻辑
- io.agentscope.core.state.PlanNotebookState 和 StatePersistence

本地 Maven 缓存中没有对应的 sources JAR，因此本文以项目实际解析到的 agentscope-1.0.12.jar 的类签名、字节码和运行时注解为依据。项目中的接入点是 src/main/java/cn/lwccxy/trip/agent/ItineraryPlanningAgent.java。

核心结论是：AgentScope 的计划模式不是一个独立的 Agent 类型，也不是 Java 线程调度器，而是以下四部分的组合：

1. PlanNotebook 保存当前计划，并通过 @Tool 把计划操作暴露给大模型。
2. DefaultPlanToHint 根据计划状态生成下一步行为提示。
3. ReActAgent.Builder 自动注册计划工具，并在每次模型推理前把提示追加为一条 USER 消息。
4. StatePersistence 和 PlanNotebookState 负责跨会话保存当前计划；PlanStorage 负责已结束计划的历史记录。

因此，计划模式本质上是“结构化状态 + 工具调用 + 每轮提示注入 + 可选持久化”。真正执行子任务的仍然是 ReAct Agent 自己的模型推理和普通工具调用。

## 2. 总体架构

    用户请求
       |
       v
    ReActAgent.doCall / executeIteration
       |
       | 每轮 PreReasoningEvent
       v
    计划 Hook 调用 PlanNotebook.getCurrentHint()
       |
       v
    DefaultPlanToHint 根据 currentPlan 生成 <system-hint>...</system-hint>
       |
       v
    把提示追加到本轮模型输入消息列表
       |
       v
    模型选择普通工具，或调用计划工具推进状态
       |
       v
    PlanNotebook 修改 Plan/SubTask，并触发 changeHooks

构建 Agent 时，ReActAgent.Builder.build() 的计划相关逻辑可以概括为：

    Toolkit copiedToolkit = toolkit.copy();
    if (planNotebook != null) {
        configurePlan(copiedToolkit);
    }
    return new ReActAgent(builder, copiedToolkit);

configurePlan 做两件事：

    toolkit.registerTool(planNotebook);
    hooks.add(new Hook() {
        // 收到 PreReasoningEvent 时，把 PlanNotebook 生成的消息追加到输入末尾。
    });

只要在 ReActAgent.Builder 上设置 .planNotebook(planNotebook)，就无需手动把计划方法逐一注册到 Toolkit。

## 3. PlanNotebook 的职责与内部字段

PlanNotebook 实现 StateModule，主要字段如下：

| 字段 | 作用 |
| --- | --- |
| currentPlan | 当前唯一活动计划，可以为 null |
| planToHint | 把计划状态转换成模型提示的策略，默认是 DefaultPlanToHint |
| storage | 历史计划存储，默认是 InMemoryPlanStorage |
| maxSubtasks | 子任务数量上限，默认 null，表示不限制 |
| needUserConfirm | 是否要求计划执行前向用户确认，默认 true |
| changeHooks | 计划发生变化时执行的同步回调，使用 ConcurrentHashMap 保存 |
| keyPrefix | 会话状态保存前缀，默认 planNotebook |

Builder 默认值相当于：

    PlanNotebook notebook = PlanNotebook.builder()
        .planToHint(new DefaultPlanToHint())
        .storage(new InMemoryPlanStorage())
        .needUserConfirm(true)
        .build();

可配置项：

- planToHint(PlanToHint)：替换提示生成器。
- storage(PlanStorage)：替换历史计划保存方式。
- maxSubtasks(int)：限制创建和新增子任务的数量。
- needUserConfirm(boolean)：控制默认提示是否包含用户确认规则。
- keyPrefix(String)：改变会话状态 key 前缀。

PlanNotebook 只保留一个 currentPlan。创建新计划会直接替换当前计划；创建时被替换的旧计划不会自动写入 PlanStorage。如果希望保留旧计划，应先调用 finish_plan，或在业务侧增加变更 Hook 和自定义存储策略。

## 4. 数据模型

### 4.1 Plan

Plan 是一个普通 Java 对象，包含：

| 属性 | 说明 |
| --- | --- |
| id | 构造时由 UUID.randomUUID() 生成 |
| name | 计划名称 |
| description | 目标、约束和要完成的事情 |
| expectedOutcome | 可验证的预期结果 |
| subtasks | 按顺序排列的 List<SubTask> |
| createdAt | 创建时间，使用 ZonedDateTime 格式化 |
| state | todo、in_progress、done、abandoned |
| finishedAt | 结束时间 |
| outcome | 计划最终结果或放弃原因 |

新建计划的初始状态是 TODO。PlanNotebook 没有专门的“开始计划”方法，计划进入执行阶段主要通过子任务状态和提示规则体现。只有 finish_plan 会调用 Plan.finish(...)，把计划设为 DONE 或 ABANDONED 并记录结束时间和结果。

### 4.2 SubTask

SubTask 包含：

- name：名称。
- description：执行说明。
- expectedOutcome：该步骤需要产出的具体数据、信息或文件。
- outcome：调用 finish_subtask 时记录的实际结果。
- state：todo、in_progress、done、abandoned。
- createdAt、finishedAt：创建和完成时间。

新建子任务默认为 TODO。finish(String) 只会把状态设为 DONE；带状态的 finish(SubTaskState, String) 只接受 DONE 或 ABANDONED，其他状态会抛出 IllegalArgumentException。

### 4.3 状态约束

update_subtask_state 可以设置 todo、in_progress 或 abandoned，不能用它设置 done。完成任务必须调用 finish_subtask，因为该方法还要写入 outcome 和完成时间。

把某个子任务设为 in_progress 时，代码强制：

1. 该子任务之前的所有子任务必须已经是 done 或 abandoned。
2. 当前计划中不能已经存在另一个 in_progress 子任务。

finish_subtask 也要求目标之前的所有子任务是 done 或 abandoned。完成后，如果后面还有一个子任务，代码会直接把紧邻的下一个子任务设置为 in_progress。

两个实现层面的注意点：

- finish_subtask 没有检查目标子任务当前必须是 in_progress；只要前置任务满足条件，模型可以直接完成 todo 子任务。
- 自动激活下一个子任务时不会再次检查它原来的状态，而是直接写成 in_progress。正常默认提示不会触发冲突，但自定义调用方需要留意。

## 5. 暴露给模型的计划工具

PlanNotebook 的公共计划方法使用 @Tool 和 @ToolParam 注解。被注册到 Toolkit 后，模型看到的是：

| 工具 | 用途 | 关键规则 |
| --- | --- | --- |
| create_plan | 创建计划和顺序子任务 | 当前计划存在时直接替换；受 maxSubtasks 限制 |
| update_plan_info | 修改名称、描述、预期结果 | 传 null 或空字符串表示保持原值 |
| revise_current_plan | 增、改、删子任务 | action 只能是 add、revise、delete |
| update_subtask_state | 设置子任务为 todo、in_progress、abandoned | 校验前置任务和单个进行中任务 |
| finish_subtask | 完成子任务并写入结果 | 按顺序完成，并自动激活下一个子任务 |
| view_subtasks | 按索引查看子任务详情 | 非法索引以文本结果返回 |
| get_subtask_count | 查看总数及各状态数量 | 没有活动计划时返回提示文本 |
| finish_plan | 完成或放弃当前计划 | 状态只能是 done 或 abandoned |
| view_historical_plans | 查看历史计划摘要 | 数据来自 PlanStorage |
| recover_historical_plan | 按 ID 恢复历史计划 | 恢复前会处理当前活动计划 |

这些方法统一返回 Mono<String>。错误通常不是抛出业务异常，而是返回一段给模型阅读的文本，例如非法索引、状态冲突或缺少活动计划。validateCurrentPlan() 是少数例外：在必须存在当前计划的方法中，未创建计划会直接抛出 IllegalStateException。

### 5.1 创建与修改计划

create_plan 接收 name、description、expected_outcome 和子任务 Map 列表。每个子任务 Map 读取三个字符串字段：

    {
      "name": "查询目的地信息",
      "description": "收集与出差目标相关的事实信息",
      "expected_outcome": "返回可引用的目的地信息列表"
    }

缺失名称会变成 Unnamed Subtask，缺失描述和预期结果会变成空字符串。revise_current_plan 通过索引操作列表：新增允许插入到 0..size，修改和删除允许 0..size-1；新增时达到 maxSubtasks 会被拒绝。

### 5.2 完成计划与历史计划

finish_plan(state, outcome) 的实际顺序是：

1. 校验当前计划存在。
2. 将 state 解析为 DONE 或 ABANDONED。
3. 在 Plan 上写入状态、结果和结束时间。
4. 调用 storage.addPlan(currentPlan) 保存历史版本。
5. 清空 currentPlan。
6. 触发计划变更 Hook。

recover_historical_plan(planId) 从存储中查找计划。如果当前计划存在且不是 DONE，会先把它标记为 ABANDONED，原因是新计划恢复打断了当前执行，然后写回存储。之后把历史计划设置为当前计划并触发 Hook。

view_historical_plans 只展示名称、ID、创建时间、描述和计划状态，不展示完整子任务。需要完整内容时，应先恢复计划或通过自定义 PlanStorage 读取。

默认 InMemoryPlanStorage 使用 ConcurrentHashMap<String, Plan>，以计划 ID 为 key；应用重启后数据会丢失。它只提供 addPlan、getPlan 和 getPlans 三个能力，没有更新、删除或分页接口。

## 6. 默认提示如何引导模型

### 6.1 提示注入方式

PlanNotebook.getCurrentHint() 调用 planToHint.generateHint(currentPlan, notebook)：

- 返回 null 或空字符串时，不追加消息。
- 有内容时，构造一条 Msg：role=USER、name=user，内容为一块文本。
- ReActAgent.Builder 注册的 Hook 在 PreReasoningEvent 中复制原输入列表，并把该消息追加到末尾。

默认提示使用以下包裹格式：

    <system-hint>
    ...
    </system-hint>

它不是模型 API 的真正 system message，而是通过 Hook 追加到当前推理输入的用户消息。名称叫 system-hint 是提示文本约定，实际消息角色仍是 USER。

### 6.2 没有计划时

默认提示告诉模型：复杂任务或长链路任务必须先调用 create_plan，简单任务可以直接执行。如果 needUserConfirm=true，还会加入确认规则：展示计划并等待用户确认；但如果用户原始请求已经明确表达执行意图，可以直接继续。

如果配置了 maxSubtasks，没有计划时的提示还会告诉模型计划不能超过该数量。

### 6.3 有计划且全部完成时

提示展示完整 Markdown 计划，并引导模型：

- 调用 finish_plan 写入具体结果并向用户总结。
- 必要时调用 revise_current_plan 修改计划。
- 用户提出无关请求时先处理该请求，再回到计划。

### 6.4 有计划但没有进行中任务时

提示会计算已经完成的前置任务数量，要求模型将下一个可执行任务设置为 in_progress。如果任务不可执行，模型应分析原因、向用户补充提问或修订计划。

### 6.5 有进行中任务时

提示会同时给出完整计划、当前子任务索引和详细子任务内容。模型可以继续执行普通工具，完成后调用 finish_subtask，或者在信息不足时向用户提问、修订计划。

默认公共规则还要求模型在处理每个子任务前调用 get_subtask_count 和 view_subtasks，以读取最新计划；允许用户直接增删改子任务；如果原始请求与最新计划冲突，以最新计划为准；没有明确的计划修改指令时不要擅自修改计划；回复语言与计划语言保持一致。

这组规则解释了 AgentScope 为什么把计划作为“每轮动态提示”而不是只在创建计划时发送一次：用户或工具可以在任意时刻修改计划，下一轮推理会重新生成提示。

## 7. 状态持久化与存储边界

### 7.1 当前计划的会话状态

PlanNotebook.saveTo(session, sessionKey) 保存一个 PlanNotebookState：

    session.save(
        sessionKey,
        keyPrefix + "_state",
        new PlanNotebookState(currentPlan)
    );

默认 key 是 planNotebook_state。loadFrom 会先把内存中的 currentPlan 清空，再从该 key 读取；找不到状态时保持 null。

### 7.2 ReActAgent 的自动管理

ReActAgent 默认使用 StatePersistence.all()，其中 planNotebookManaged=true。因此只要 Agent 配置了 planNotebook，Agent 的 saveTo 和 loadFrom 会自动调用 Notebook 的对应方法。

如果业务使用 StatePersistence.none()，或一个把 planNotebookManaged 设为 false 的自定义配置，当前计划不会由 Agent 自动保存，需要业务自行调用 saveTo/loadFrom。

### 7.3 当前计划与历史计划不是同一个存储

- PlanNotebookState：保存当前正在执行的计划，跟随 Agent 会话状态。
- PlanStorage：保存已经完成、放弃或被恢复流程打断的计划，默认只在 JVM 内存中保存。

因此，配置了 MySQL Session 并不等于历史计划也会落到 MySQL。当前项目使用 MysqlSession 保存 Agent 会话状态，但 PlanNotebook 仍使用默认 InMemoryPlanStorage；应用重启后，当前计划可以从会话恢复，历史计划则会丢失，除非另行注入持久化 PlanStorage。项目中的行程规划 Agent 是 prototype 作用域，每次创建出的 PlanNotebook 也会创建新的默认内存存储；如果 Agent 实例不是在同一生命周期内复用，历史计划甚至可能在应用未重启时也不可见。

## 8. 当前项目的接入方式

项目在 ItineraryPlanningAgent.itineraryPlanningAgent() 中使用：

    PlanNotebook planNotebook = PlanNotebook.builder()
        .needUserConfirm(false)
        .build();

    return ReActAgent.builder()
        // ...
        .planNotebook(planNotebook)
        .build();

这段配置产生的实际效果是：

1. 计划工具自动加入当前 Agent 的 Toolkit。
2. 默认提示仍然引导复杂任务先创建计划、按顺序执行子任务和最后结束计划。
3. 计划执行前不要求额外的用户确认，因为项目显式设置了 needUserConfirm(false)。
4. 当前计划随 Agent 的 StatePersistence 保存到项目配置的 Session 实现中。
5. 已完成计划仍保存在 Notebook 默认的 JVM 内存存储中，不会自动写入 agentscope_sessions 表。

项目当前没有启用 addChangeHook。如果要向前端推送计划进度，可以注册 Hook，在创建计划、修改子任务、改变状态和结束计划后读取 PlanNotebook.getCurrentPlan()，再通过现有进度通知机制发送事件。Hook 是同步 BiConsumer<PlanNotebook, Plan>；如果需要异步 I/O，应在 Hook 中自行投递任务，避免阻塞模型调用链。

## 9. 自定义与扩展建议

### 9.1 自定义提示策略

实现 PlanToHint 可以改变模型看到的流程规则，例如针对企业出差任务增加“先核验日期和城市，再调用实时工具”的要求：

    PlanToHint customHint = (plan, notebook) -> {
        // 根据 plan 的状态和子任务生成文本；返回空字符串表示本轮不追加提示。
        return "...";
    };

    PlanNotebook notebook = PlanNotebook.builder()
        .planToHint(customHint)
        .needUserConfirm(false)
        .build();

自定义提示应保持短、明确，并且与工具真实约束一致。提示只能引导模型，不能替代 PlanNotebook 的 Java 校验。

### 9.2 自定义历史存储

实现 PlanStorage 的三个方法即可接入数据库、Redis 或其他存储：

    public interface PlanStorage {
        Mono<Void> addPlan(Plan plan);
        Mono<Plan> getPlan(String planId);
        Mono<List<Plan>> getPlans();
    }

生产实现至少应考虑：会话或用户隔离、计划 ID 唯一性、历史计划排序、并发写入、敏感信息脱敏和查询数量限制。当前接口没有删除能力，清理策略需要在自定义实现内部完成。

### 9.3 进度通知与前端展示

推荐把 changeHooks 当作“状态变化通知”，而不是业务事务边界。数据库写入、消息发布和前端推送的失败处理要由业务 Hook 自己定义；不要假设 Hook 的执行与 Agent 的 Session 保存自动组成一个事务。

## 10. 关键行为时序

    1. 用户发送复杂请求
    2. PreReasoning Hook 发现 currentPlan == null
    3. DefaultPlanToHint 提示模型调用 create_plan
    4. 模型调用 create_plan
    5. PlanNotebook 创建 Plan 和 SubTask，触发 changeHooks
    6. 下一轮推理再次注入最新计划提示
    7. 模型调用 update_subtask_state(index, "in_progress")
    8. 模型执行普通业务工具
    9. 模型调用 finish_subtask(index, outcome)
    10. PlanNotebook 完成当前子任务并激活下一个
    11. 重复第 6 步到第 10 步
    12. 模型调用 finish_plan("done", outcome)
    13. Plan 写入 PlanStorage，currentPlan 清空，触发 changeHooks

如果 Agent 在中途暂停并由 Session 恢复，恢复的是 currentPlan 的快照；恢复后下一次 PreReasoningEvent 会基于该快照重新生成提示，不依赖之前的提示文本是否仍在短期消息中。

## 11. 实现边界与风险点

1. 计划不是工作流引擎。AgentScope 不会自动运行子任务，也不会根据计划启动并行线程；模型必须主动调用计划工具和普通工具。
2. 顺序是列表约束。计划没有依赖图、条件分支或并行组，前置关系由索引顺序和状态校验表达。
3. 提示不是强制安全边界。模型可能不遵循提示；真正可靠的约束来自 updateSubtaskState、finishSubtask 等 Java 方法。
4. 默认历史存储不持久化。InMemoryPlanStorage 只适合单 JVM 或示例场景，多实例和重启场景应注入持久化实现。
5. 创建新计划会覆盖当前引用。旧活动计划不会自动进入历史存储，需要业务明确处理。
6. 索引会因修改而变化。增删子任务后，模型必须重新调用 get_subtask_count 和 view_subtasks，不能继续使用旧索引。
7. 提示消息角色是 USER。<system-hint> 只是文本标记，不能把它当作真正的 system message 或安全策略。
8. 计划状态与子任务状态不完全同步。创建后计划状态通常仍是 todo，完成某个子任务不会自动把计划设为 in_progress；计划最终状态只在 finish_plan 时明确写入。

## 12. 针对 CorpTripAgent 的落地建议

结合当前项目的企业出差助手场景，建议后续按以下方向增强：

- 保留 .needUserConfirm(false)，由现有确认 Hook 或业务规则处理需要用户确认的订票、改签和下单动作，避免把所有计划确认混成一个开关。
- 为 PlanNotebook 增加一个按用户和会话隔离的持久化 PlanStorage，不要让不同用户共享 JVM 级别的历史计划。
- 使用 addChangeHook 将计划、当前子任务和状态变化映射到现有 ProgressNotificationHook，前端可以据此展示可恢复的进度。
- 在项目系统提示词中明确：每次处理子任务前重新读取最新计划，完成任务时 outcome 必须填写具体数据、信息或文件路径，而不是只写“已完成”。
- 为计划工具补充针对空列表、错误类型、重复状态和历史恢复的单元测试；这些边界直接影响模型能否从错误文本中自我纠正。
- 如果计划内容包含用户身份、出差地点、预算等敏感信息，持久化前应按项目现有日志和数据脱敏规范处理。

## 13. 一句话总结

AgentScope Java 的计划模式通过 PlanNotebook 把计划变成 Agent 可操作的工具状态，再由 PreReasoning Hook 在每轮推理前生成动态提示；模型负责选择和执行步骤，Notebook 负责状态约束和结果记录，Session 负责当前计划快照，PlanStorage 负责历史计划。四者组合后，普通 ReAct Agent 就获得了可修改、可恢复、可观察的计划执行能力。

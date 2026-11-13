# 智能体时间线 SSE 协议

`ProgressNotificationHook` 将工具执行和智能体文本增量通过现有 SSE 连接发送。SSE 的 `event` 名称与 JSON 的 `eventType` 一致；`data` 是 JSON 对象，前端对 `event.data` 执行一次 `JSON.parse` 即可。

通知范围为已注册该 Hook 的智能体。目前主智能体和通用信息智能体已注册；行程单智能体被主智能体调用时会展示外层工具调用，但其内部事件需要在自身的 `hooks` 中注册该 Hook 后才会推送。

## 统一字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| eventId | string | 本次推送的唯一标识，不是工具调用关联键 |
| eventType | string | `tool_start`、`tool_result`、`thinking` |
| agentId | string | 智能体实例标识，用于区分并行智能体 |
| agentName | string | 智能体中文展示名称；未配置时保留原名 |
| tooldesc | string | 工具或当前动作的描述，保留约定的小写拼写 |
| timestamp | number | 原始 Hook 事件产生时的 Unix 毫秒时间戳 |
| toolCallId | string / null | 工具调用标识，开始和结果事件一致 |
| toolName | string / null | 工具原名，包括作为工具被调用的子智能体名称 |
| input | object / null | 原始工具入参；开始和结果事件均携带对象，文本事件为 null |
| output | string / null | 工具结果文本或本次文本增量；工具开始时为 null |
| messageId | string / null | 同一条智能体消息的文本增量关联标识 |
| status | string | `running`、`completed`、`suspended` |

所有字段始终保留。无入参的工具使用 `{}`，无文本的工具结果使用 `""`。未配置的工具描述回退为工具原名，避免不同工具都展示为“未知”。

## 工具结果示例

```json
{
  "eventId": "notification-2",
  "eventType": "tool_result",
  "agentId": "agent-instance-1",
  "agentName": "主智能体",
  "tooldesc": "获取用户信息",
  "timestamp": 1793491200123,
  "toolCallId": "call-1",
  "toolName": "get_userinfo",
  "input": {},
  "output": "工具返回的文本",
  "messageId": null,
  "status": "completed"
}
```

对应的 `tool_start` 使用相同的 `agentId`、`toolCallId`、`toolName` 和 `input`，`output` 为 null，`status` 为 `running`。工具等待外部处理时结果事件的 `status` 为 `suspended`。

## 文本增量示例

```json
{
  "eventId": "notification-3",
  "eventType": "thinking",
  "agentId": "agent-instance-1",
  "agentName": "主智能体",
  "tooldesc": "智能体输出",
  "timestamp": 1793491200456,
  "toolCallId": null,
  "toolName": null,
  "input": null,
  "output": "正在查询",
  "messageId": "message-1",
  "status": "running"
}
```

## 前端归并规则

1. 分别监听 `tool_start`、`tool_result`、`thinking`，每种事件都使用上述统一结构。
2. 工具节点使用 `agentId + ':' + toolCallId` 作为关联键：开始时创建节点，结果到达时更新原节点的 `output` 和 `status`。即使仅收到结果事件，也可以使用其中的名称和入参创建完整节点。
3. 文本节点使用 `agentId + ':' + messageId` 关联，把每次 `output` 追加到已有文本；不得重复追加累计消息。空格增量也需要保留。`thinking` 表示 Hook 接收到的智能体可见文本，并不表示模型私有思维链。
4. `timestamp` 用于显示发生时间，相同毫秒内沿用接收顺序。事件没有全局序号或断线重放能力，不能只靠时间戳或事件标识重建严格执行顺序。
5. `completed` 只表示工具已返回结果，不保证业务成功；应按工具自身的返回协议判断业务结果。当前结构不会通过文本关键词猜测成功或失败。
6. 现有 `delta` 仍用于主回答区域；`thinking` 用于时间线，两者不要同时追加到同一个正文容器。整轮结束沿用现有 `complete`、`error` 或 `ask` 事件。

## 边界说明

- 工具输出按内容块顺序提取 `TextBlock`，以 `\n` 拼接，图片和音频等非文本块不包含在 `output` 中。
- 进度 Hook 使用订阅上下文中的 `agentRuntimeContext` 查找当前会话连接；缺少上下文或连接时跳过通知，继续原始智能体执行。
- Hook 的返回类型仍为 `Mono<T>`，不会把 JSON 替换进 AgentScope 事件流；统一 JSON 由 SSE 的 `data` 单独输出。
- 本次仅实时推送，不新增数据库记录或恢复历史时间线的接口。日志不记录工具入参和结果正文。

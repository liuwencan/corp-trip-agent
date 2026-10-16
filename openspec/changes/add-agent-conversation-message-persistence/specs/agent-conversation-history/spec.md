# Spec Delta

## Purpose

为 Agent 对话提供按用户隔离的会话和消息持久化，支撑侧边栏历史列表、消息回看和继续对话。会话按更新时间排序，消息保留归属与创建信息，并允许用户对自己的会话中的 Agent 回复设置、切换和取消点赞点踩。

## ADDED Requirements

### Requirement: Users can create and manage owned conversations

系统 SHALL 以会话 ID 标识会话，从当前认证用户确定归属；新会话为 ACTIVE。会话 SHALL 保存标题、运行时 session 映射、消息计数、创建时间和更新时间。仅所属用户能读取、改名、归档或关闭会话，所有入口 MUST 校验归属。

#### Scenario: Create a conversation

- **WHEN** 用户创建会话，提交合法标题或省略标题
- **THEN** 系统返回所属用户的 ACTIVE 会话 ID，标题使用提交值或“新会话”，计数为 0，创建/更新时间已初始化且存在运行时映射

#### Scenario: Reject access to another user's conversation

- **WHEN** 用户以其他用户的会话 ID 请求任意会话、消息或反馈操作
- **THEN** 系统返回与资源不存在相同的错误，不返回资源数据或修改记录

#### Scenario: Archive or close an owned conversation

- **WHEN** 用户将自己的 ACTIVE 会话归档或关闭，或将 ARCHIVED 会话关闭
- **THEN** 系统保存新会话状态和更新时间，保留所有历史消息；非 ACTIVE 会话不再接收新消息，默认 ACTIVE 列表不包含它

#### Scenario: Reject unsupported state transitions

- **WHEN** 用户尝试重新激活 ARCHIVED 或 CLOSED 会话
- **THEN** 系统返回状态错误且不修改会话；重复提交当前状态为无操作

### Requirement: Conversation history is pageable and stable

系统 SHALL 提供会话列表、会话详情和消息分页，普通查询返回 Response/PageResponse。会话 MUST 按 updateTime 降序、ID 降序排列，消息 MUST 按创建时间升序、ID 升序排列；页码至少为 1，页大小为 1–100。

#### Scenario: List recent conversations

- **WHEN** 用户查询本人会话列表
- **THEN** 返回本人会话 ID、标题、会话状态、消息计数、创建/更新时间及正确分页信息；相同更新时间的会话按 ID 降序排列

#### Scenario: Read messages with equal creation times

- **WHEN** 用户读取本人会话消息，其中多条消息创建时间相同
- **THEN** 按 ID 升序稳定排列，返回消息 ID、角色、正文、模型名称（如有）、创建时间及反馈信息

#### Scenario: Reject invalid pagination or identifiers

- **WHEN** 请求使用非正整数资源 ID、页码小于 1 或页大小不在 1–100
- **THEN** 返回统一参数错误，不读取其他用户资源

### Requirement: Message persistence updates conversation activity atomically

系统 SHALL 保存消息 ID、会话/用户归属、角色、正文、可选模型名称、创建人和创建时间；支持 USER、ASSISTANT、SYSTEM、TOOL 角色。每条消息保存 MUST 与所属会话计数递增和 updateTime 更新原子完成。消息历史不要求独立顺序号或重试去重，相同内容再次提交视为新消息。

#### Scenario: New message refreshes sidebar ordering

- **WHEN** 任意角色的新消息成功写入会话
- **THEN** 消息持久化、计数递增和会话更新时间同时提交，随后列表按照新更新时间排序

#### Scenario: Roll back a failed append

- **WHEN** 消息插入或会话计数/时间更新失败
- **THEN** 整个事务回滚，不保留孤立消息、错误计数或单独的会话时间变化

#### Scenario: Submit the same content twice

- **WHEN** 用户对本人 ACTIVE 会话分别提交两次相同文本
- **THEN** 系统将其视为两条独立用户消息，不按文本内容去重

#### Scenario: Reject writes to archived or closed conversations

- **WHEN** 用户向非 ACTIVE 会话发送新消息
- **THEN** 返回会话状态错误，不新增消息、不增加计数或改变更新时间

### Requirement: Conversation updates drive sidebar activity

系统 SHALL 在标题或会话状态实际改变时更新会话 updateTime；反馈变更 MUST NOT 更新会话 updateTime。消息反馈或其他字段操作 MUST NOT 改变消息创建时间。

#### Scenario: Rename a conversation

- **WHEN** 用户更改本人会话标题
- **THEN** 保存标题并刷新会话更新时间，侧边栏按更新时间重新排序

#### Scenario: Feedback does not reorder conversations

- **WHEN** 用户对旧会话中的 Assistant 消息点赞、点踩或取消反馈
- **THEN** 会话更新时间和消息计数、消息创建时间均保持不变

### Requirement: Agent replies map to owned business conversations

系统 SHALL 使用认证用户及会话对应的运行时 session 加载上下文，先保存用户消息再调用 Agent，完整回复保存成功后才确认完成。失败或中断 SHALL 通过 HTTP 或 SSE 错误通知，保留已入库消息，不新增虚构回复或持久化消息失败状态；未完成流式片段不保证历史恢复。

#### Scenario: Continue an existing conversation

- **WHEN** 用户向本人 ACTIVE 会话发送合法消息
- **THEN** 系统持久化用户消息，使用已保存的 session 映射调用 Agent，完整回复作为该会话的 ASSISTANT 消息持久化

#### Scenario: Agent or persistence fails

- **WHEN** Agent 超时、异常、中断，或完整回复无法入库
- **THEN** 返回统一 HTTP 错误或 SSE error 事件，不发送成功完成通知，不写入虚构回复；此前已入库用户消息保留

### Requirement: Users can rate their assistant messages

系统 SHALL 允许会话所属用户对 ASSISTANT 消息设置 LIKE、DISLIKE 或 NULL。反馈值和反馈时间 MUST 原子更新：首次设置或切换时使用服务端当前时间，重复相同值保持时间，取消时两个字段都为 NULL。仅记录当前反馈，不保留变更历史。

#### Scenario: Set or switch feedback

- **WHEN** 用户对本人会话的 ASSISTANT 消息首次点赞或从 LIKE 切换为 DISLIKE
- **THEN** 保存新反馈和服务端反馈时间，响应及后续历史查询返回相同值

#### Scenario: Repeat or cancel feedback

- **WHEN** 用户重复设置相同反馈或显式提交 NULL 取消反馈
- **THEN** 相同反馈不刷新时间，取消反馈清空反馈及反馈时间

#### Scenario: Reject invalid feedback or role

- **WHEN** 请求缺少反馈字段、提交非法值，或尝试对非 ASSISTANT 消息设置反馈
- **THEN** 返回 INVALID_PARAMETER，原反馈及时间保持不变

#### Scenario: Feedback on retained history

- **WHEN** 用户给本人已归档或关闭会话中的 ASSISTANT 消息反馈
- **THEN** 系统允许更新当前反馈，保留会话状态和排序时间

### Requirement: Minimal provenance is retained without exposing credentials

系统 SHALL 保留会话用户归属、创建/更新时间与会话级链路标识，以及消息归属、创建人/时间和反馈时间。消息创建人 MUST 来自服务端认证用户。历史仅按本人归属读取，日志 MUST NOT 输出密码、Token、完整请求头或消息正文；本版不提供逐请求或反馈变更历史审计。

#### Scenario: Preserve history after archive

- **WHEN** 用户归档或关闭会话
- **THEN** 会话与消息继续可按归属查询，原创建信息保留，会话更新时间反映状态修改

#### Scenario: Reject provenance overrides

- **WHEN** 客户端提交其他用户 ID、消息创建人或反馈时间试图覆盖服务端字段
- **THEN** 受保护字段不能覆盖归属、创建人或反馈时间

# Proposal

## Why

当前 Agent 聊天接口使用临时 `sessionId`，缺少按用户保存、查询和展示会话及消息的业务持久化能力。需要建立用户、会话、消息的一对多关系，以支持侧边栏历史会话、消息回看和用户对 Agent 回复的反馈。

## What Changes

- 新增 `t_conversation`、`t_message`，建立 `t_user` → `t_conversation` → `t_message` 的逻辑关联，并提供独立迁移 SQL。
- 会话以主键 ID 对外引用，保存用户归属、标题、会话状态、运行时 session 映射、消息计数、会话级链路标识及创建/更新时间。侧边栏按 `update_time DESC, id DESC` 排序，每次消息入库同步更新会话时间和计数。
- 消息以主键 ID 对外引用，保存用户归属、角色、正文、可选模型名称、创建人和创建时间，按 `create_time ASC, id ASC` 展示；新增 `feedback` 和 `feedback_at`，支持对本人会话中的 ASSISTANT 消息点赞、点踩和取消反馈。
- 会话不保存最后消息摘要、独立的最后消息时间、来源渠道、业务编号或创建/更新操作人；消息不保存业务编号、客户端幂等键、顺序号、格式字段、处理状态、结束原因、链路/请求标识或更新操作人/更新时间。
- 接入当前认证用户和会话归属校验，提供会话创建、改名、归档/关闭、会话/消息分页历史及反馈接口，使用 `Response`/`PageResponse`。
- 用户消息先持久化，再调用 Agent；完整回复入库后确认完成。调用失败或中断通过 HTTP/SSE 错误通知，保留已入库用户消息，不新增虚构回复。当前方案不提供消息重试去重或持久化调用状态。
- 保留业务会话与 AgentScope `sessionId` 的映射，运行时状态和业务历史分别存储。

## Capabilities

### New Capabilities

- `agent-conversation-history`: 按用户隔离的会话管理、消息持久化与历史分页、Agent 回复保存和点赞点踩反馈。

### Modified Capabilities

无。

## Impact

- 在 `agent/conversation` 增加 Entity、Mapper、Form、VO、Service、Controller，接入现有聊天编排和 `CurrentUser`。
- 扩展 `sql/init.sql` 并新增独立建表迁移，保持现有配置、Prompt、用户及 AgentScope 表结构。
- API 使用 `conversationId` 和 `messageId`，同步调整会话查询、消息发送、反馈、Agent 接入、测试和使用文档。
- 使用现有 MySQL、MyBatis-Plus、鉴权和响应机制；范围包括必要的服务、Web 和数据库验证，不增加 Redis、消息队列或审计表。

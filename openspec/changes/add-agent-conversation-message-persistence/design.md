# Design

## Context

动机见 proposal.md。项目已使用 MyBatis-Plus、MySQL、Sa-Token 和统一响应。现有聊天接口使用临时 sessionId 和固定用户 ID；业务历史表尚未落地。本次按用户确认的精简字段调整规划，保留用户归属、创建时间和必要运行映射；新增消息反馈。

## Goals / Non-Goals

**Goals:**

- 两张表满足侧边栏、历史消息、会话生命周期和反馈展示。
- 明确消息写入、会话计数及更新时间的事务一致性。
- 所有接口通过当前用户校验会话归属，整数主键也不能绕过授权。

**Non-Goals:**

- 本版不实现消息重试去重、持久化调用状态、逐请求审计或消息编辑。
- 不增加辅助表，不替换 AgentScope 状态表，不增加分布式同步或数据清理任务。

## Decisions

### 1. 最终字段

采用逻辑关联，不新增数据库外键。现有表实际结构在 apply 阶段只读核验，本次未连接数据库。以下字段清单作为后续 SQL、Entity、接口的依据。

`t_conversation`：

| 字段 | 类型/约束 | 用途 |
| --- | --- | --- |
| `id` | bigint AUTO_INCREMENT PRIMARY KEY | 会话 ID，同时作为接口标识 |
| `user_id` | bigint NOT NULL | 归属用户 |
| `title` | varchar(200) NOT NULL | 展示标题，未提供时使用“新会话” |
| `status` | varchar(16) NOT NULL DEFAULT 'ACTIVE' | ACTIVE / ARCHIVED / CLOSED，会话级状态 |
| `runtime_session_id` | varchar(255) NOT NULL | 服务端生成的 AgentScope sessionId |
| `message_count` | int NOT NULL DEFAULT 0 | 已持久化消息总数 |
| `trace_id` | varchar(64) DEFAULT NULL | 保留原有会话级最近链路标识；不代表逐消息请求审计 |
| `create_time` | datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 创建时间 |
| `update_time` | datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) | 会话更新时间 |

不包含 `last_message_preview`、`last_message_at`、`source`、`conversation_no`、`created_by`、`updated_by`。用户所指 create_by/update_by 对应原方案中的 created_by/updated_by，均删除。

索引：`(user_id, update_time, id)` 支持用户会话列表，`(user_id, status, update_time, id)` 支持状态筛选；`runtime_session_id` 使用唯一索引保证映射不冲突。

`t_message`：

| 字段 | 类型/约束 | 用途 |
| --- | --- | --- |
| `id` | bigint AUTO_INCREMENT PRIMARY KEY | 消息 ID，同时作为接口标识 |
| `conversation_id` | bigint NOT NULL | 所属会话 |
| `user_id` | bigint NOT NULL | 冗余归属，与会话 user_id 一致 |
| `role` | varchar(16) NOT NULL | USER / ASSISTANT / SYSTEM / TOOL |
| `content` | longtext NOT NULL | 消息正文，按文本返回；结构化正文由发送方编码为字符串 |
| `model_name` | varchar(128) DEFAULT NULL | 可选模型名称 |
| `created_by` | bigint NOT NULL | 创建操作的认证用户 ID；Agent 消息也记录发起调用的用户，以 role 区分发送方 |
| `create_time` | datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 消息写入时间 |
| `feedback` | varchar(16) DEFAULT NULL | 用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈 |
| `feedback_at` | datetime DEFAULT NULL | 反馈时间 |

不包含 `sequence_no`、`client_message_id`、`message_no`、`content_format`、`finish_reason`、`status`、`trace_id`、`request_id`、`update_time`、`updated_by`。

索引：`(conversation_id, create_time, id)` 支持历史分页；归属以会话校验和查询条件共同保证。feedback/feedback_at 直接记录当前反馈，不增加反馈版本或操作日志。

### 2. 排序与时间更新

侧边栏 SQL 为 `ORDER BY update_time DESC, id DESC`。创建时初始化时间；每次插入任意角色消息，都在同一短事务内更新会话 `message_count = message_count + 1` 和 `update_time`。改名、归档和关闭也更新会话时间，因此会改变侧边栏位置。

不能只依赖会话表的 ON UPDATE：插入消息不会自动触发会话表更新。并发写入先锁定所属会话行，再插入消息和更新计数/时间，事务回滚时三者一起回滚。时间使用服务端统一时区，显式更新时间以数据库时间为准，并避免并发使用旧时间覆盖新值。

消息 SQL 为 `ORDER BY create_time ASC, id ASC`，同一时间由 ID 决定顺序；表示入库顺序，不是 Agent 内部事件序列。并发插入与读取通过事务和稳定排序保证可解释性，页码分页不承诺跨多次查询的快照一致性。

反馈仅更新消息的 feedback/feedback_at，不更新消息 create_time、不改变会话 update_time 和 message_count。反馈不会将旧会话移到侧边栏顶部。

### 3. 接口、校验与反馈语义

在 `agent.conversation` 下按 Entity、Mapper、Form、VO、Service、Controller 分层，接口路径统一使用正整数 conversationId/messageId：

| 接口 | 用途 |
| --- | --- |
| POST /agent/conversations | 创建会话 |
| GET /agent/conversations | 分页查询当前用户会话，默认 ACTIVE，可筛选状态 |
| GET /agent/conversations/{conversationId} | 详情 |
| PUT /agent/conversations/{conversationId} | 修改标题或归档/关闭 |
| GET /agent/conversations/{conversationId}/messages | 消息分页 |
| POST /agent/conversations/{conversationId}/messages | 发送用户文本并调用 Agent |
| PUT /agent/conversations/{conversationId}/messages/{messageId}/feedback | 设置或取消反馈 |

普通接口使用 Response/PageResponse。发送接口衔接现有 SSE 事件，初始化和完成事件包含 conversationId/messageId；开始流之前可用统一 HTTP 错误，开始后使用 SSE error 事件，不能混写普通 JSON 响应。

Form 校验：ID > 0，标题去除首尾空白后 1–200 字符（创建时可省略），消息非空且最多 20000 字符，分页 pageNo >= 1、pageSize 1–100，状态只接受枚举值。所有归属与 created_by 由认证用户决定，客户端不能覆盖。

会话状态保留 ACTIVE/ARCHIVED/CLOSED：ACTIVE 可归档或关闭，ARCHIVED 可关闭；重复设置当前状态为无操作，不支持重新激活。非 ACTIVE 会话可读取历史和反馈，不能追加消息。更新会话时只更新提交的标题/状态，不能用旧 Entity 覆盖计数和更新时间。

反馈请求显式提交 `{"feedback":"LIKE"}`、`{"feedback":"DISLIKE"}` 或 `{"feedback":null}`；字段缺失、空字符串或其他值返回 INVALID_PARAMETER。仅会话所属用户可对该会话的 ASSISTANT 消息操作；归属不符或消息不在该会话统一返回资源错误，非 ASSISTANT 返回参数错误。新消息两个反馈字段均为 NULL。设置/切换反馈时两字段原子更新，时间取服务端；重复相同反馈保持原时间；取消时两者都置 NULL。并发反馈按数据库更新串行化，最后一次有效写入生效。VO 返回当前反馈及反馈时间。

### 4. Agent 接入与失败处理

现有 ChatController 使用 CurrentUser 获取用户，按 conversationId 查归属和 runtime_session_id。创建会话生成随机 runtime sessionId，后续不接受客户端覆盖。

先提交用户消息和会话更新，再在事务外调用 Agent；收集完成的 Assistant 回复，保存成功后才发送最终完成事件。失败或中断保留已入库用户消息，通过 HTTP/SSE 提示错误；不写入虚构回复，不把错误文本混入正常历史。不保存未完成片段；SSE 已发出的片段在失败后不一定能从历史恢复。Assistant 持久化失败也必须返回错误，不能宣告完成。

删除客户端幂等键后，同一文本重复提交视为独立消息，可能多次调用 Agent；客户端需避免重复提交，服务端不承诺跨重试去重。同一会话的 Agent 调用在单 JVM 内串行执行，避免运行时上下文并发改写；非 ACTIVE 会话拒绝新调用。正在处理时的归档/关闭需等待当前调用结束或返回冲突，避免关闭后继续落库；多实例协调不在本版范围内。

### 5. 最小追溯与敏感信息

归属通过 user_id 追溯，消息通过 created_by/create_time 记录发起人和入库时间，会话保留 create_time/update_time 和会话级 trace_id；反馈通过 feedback_at 记录最近有效变更时间。这些字段不构成逐请求审计或反馈变更历史。错误日志只记录必要 ID、错误类别，不输出消息原文、凭据或完整请求头；不承诺能从自由文本识别所有敏感信息。

## Risks / Trade-offs

- [更新时间排序] → 改名或状态变更会让会话前移，这是本版确认的排序语义；反馈不触碰会话时间。
- [重试重复] → 明确一次新请求就是一次新消息，文档不再承诺幂等追加。
- [故障信息不在消息表] → 接口/SSE 返回错误，历史仅显示实际保存的内容，不支持持久化失败状态或逐请求关联。
- [反馈可覆盖] → 只保存当前值和时间，取消后不保留旧值、旧时间。
- [运行时并发] → 单实例内按会话协调调用；多实例部署需要后续独立设计。

## Migration Plan

1. apply 时只读核验目标数据库与 t_user 及 AgentScope 表结构，确认新表无冲突并备份。
2. 更新 sql/init.sql，并提供独立 V<date>__agent_conversation_message.sql；先会话后消息，验证实际列、索引、默认值和 NULL 反馈语义。MySQL DDL 不依赖事务回滚。
3. 先部署迁移再启动应用，验证消息写入推动侧边栏排序、计数回滚和反馈行为；启动逻辑不执行 DDL。
4. 回滚应用前停止写入并导出新增表数据；保留表数据以便恢复，显式删除新增表时先消息后会话。既有业务表与 AgentScope 数据不修改。

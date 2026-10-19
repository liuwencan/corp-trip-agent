# Agent 会话与消息历史

## 数据库部署

新增 `t_conversation`、`t_message`，用户 → 会话 → 消息为逻辑关联，不创建物理外键。精简字段以 `sql/migration/V20261016__agent_conversation_message.sql` 为准；`sql/init.sql` 包含相同新表定义。业务代码位于 `business.conversation`，Agent 运行时状态仍由 AgentScope 保存。

执行前只读检查目标数据库、`SHOW TABLES`、`SHOW CREATE TABLE t_user`、AgentScope 状态表及新表命名冲突，并备份现有数据。2026-10-16 本地核验时，原 `corptrip` 仅有 `t_config`、`agentscope_sessions`，缺少 `t_user`；本次只在独立验证库执行建表，未修改原库。

已有环境先完成 `V20261014__user_auth_satoken.sql` 的前置核验及用户迁移，再执行 `V20261016__agent_conversation_message.sql`：先会话表，后消息表。脚本不使用 `IF NOT EXISTS` 掩盖结构冲突，不能重复执行。全新环境使用完整 `sql/init.sql`，不再重复运行同一建表迁移。

执行后保存两表 `SHOW CREATE TABLE` 结果，核对列、默认值、索引、主键。会话/消息创建时间及会话更新时间为 `datetime(6)`，反馈时间为 `datetime`；两个反馈字段默认 NULL。运行 session 唯一索引保证映射唯一，消息索引为 `(conversation_id, create_time, id)`。用户、会话归属一致性由服务事务和查询条件保证。

应用启动不执行本次 DDL。MySQL DDL 隐式提交，不能依赖事务回滚。回滚前停止新会话及消息写入，导出两张新增表的数据并验证备份；通常只回退应用并保留表供恢复。确需移除时由运维显式先删除消息表、后删除会话表。不得清理既有业务表或 AgentScope 状态。

## API

所有接口需要登录，使用项目的 `satoken` 请求头。归属、创建人及时间由服务端决定；JSON 中提交未知字段返回 400。路径 ID 必须为正整数。不存在及跨用户资源统一返回 404 `RESOURCE_NOT_FOUND`。

| 方法及路径 | 请求/用途 |
| --- | --- |
| POST `/agent/conversations` | `{}` 或 `{"title":"上海出差"}` 创建会话 |
| GET `/agent/conversations` | `pageNo=1&pageSize=20&status=ACTIVE` 会话列表 |
| GET `/agent/conversations/{conversationId}` | 会话详情 |
| PUT `/agent/conversations/{conversationId}` | `{"title":"新标题"}` 或 `{"status":"ARCHIVED"}`，也可同时提供 |
| GET `/agent/conversations/{conversationId}/messages` | `pageNo=1&pageSize=20` 消息历史 |
| POST `/agent/conversations/{conversationId}/messages` | `{"content":"帮我规划出差"}`，SSE 响应 |
| PUT `/agent/conversations/{conversationId}/messages/{messageId}/feedback` | 设置或取消反馈 |

创建标题可省略或为 null，默认“新会话”；提供时去除首尾空白后为 1–200 字符。改名同样校验，更新请求至少包含一个非 null 标题或状态。发送正文不能为空白，最多 20000 字符。pageNo 为 1–2147483647，pageSize 为 1–100，越页返回空数组。

会话默认 ACTIVE，可变为 ARCHIVED 或 CLOSED；ARCHIVED 仅可进一步变为 CLOSED。不支持重新激活，非法转换返回 409 `CONVERSATION_STATE_INVALID`。重复设置当前状态且未改标题为无操作。非 ACTIVE 仍可读历史及反馈，禁止追加消息。列表默认 ACTIVE，可筛选 ARCHIVED、CLOSED，或使用 ALL 查询全部状态。

普通响应使用 `Response`，例如会话详情：

```json
{"success":true,"message":null,"errorCode":null,"data":{"id":1,"title":"上海出差","status":"ACTIVE","messageCount":0,"createTime":"2026-10-16T10:00:00","updateTime":"2026-10-16T10:00:00"}}
```

分页使用 `PageResponse`，`data` 为数组，另包含 `pageNo`、`pageSize`、`totalCount`、`totalPage`。会话按 `update_time DESC, id DESC` 排列；消息按 `create_time ASC, id ASC` 排列。同时间用主键稳定排序，页码分页不保证跨请求快照一致性。消息 VO 为 `id, conversationId, role, content, modelName, createTime, feedback, feedbackAt`，不会暴露运行时 session 或会话 trace。

每条任意角色消息入库时，锁定所属会话行，在同一事务中插入消息、递增计数并用数据库时间更新会话时间；任一步失败整体回滚。改名或状态实际变化也刷新会话时间。消息插入不会自动触发另一张表的 `ON UPDATE`，因此代码显式更新会话。反馈不会改变侧边栏排序时间或消息创建时间。

## 点赞点踩

请求必须显式包含 feedback：`{"feedback":"LIKE"}` 点赞、`{"feedback":"DISLIKE"}` 点踩、`{"feedback":null}` 取消。缺失字段、空字符串及其他值返回 400 `INVALID_PARAMETER`；不能提交 feedbackAt。仅会话所属用户可对该会话的 ASSISTANT 消息操作，非 ASSISTANT 返回 400。

成功返回 `Response<MessageVO>`，例如取消后：

```json
{"success":true,"message":null,"errorCode":null,"data":{"id":2,"conversationId":1,"role":"ASSISTANT","content":"已为你整理行程","modelName":"configured-model","createTime":"2026-10-16T10:01:00","feedback":null,"feedbackAt":null}}
```

首次设置及切换时使用数据库当前时间，同时更新两字段；重复相同值保留原时间；取消时两字段显式置 NULL。并发反馈通过消息行锁串行处理，最后一次有效写入生效。只保留当前反馈，不保留反馈变更历史，已归档/关闭的会话仍允许反馈。

## SSE 与继续会话

旧 `POST /chat/chat` 使用 `{"conversationId":1,"question":"继续规划"}`，与新发送接口共用持久化服务。旧 sessionId 参数已移除；先创建业务会话，再使用返回的 ID。当前用户来自认证上下文，运行时 sessionId 由服务端生成并在继续对话时复用。

用户消息先提交，再在数据库事务外调用 Agent；完整 Assistant 回复保存成功后才发送 complete。事件示意：

```text
event:init
data:{"conversationId":1,"messageId":1}

event:delta
data:{"conversationId":1,"delta":"行程建议"}

event:complete
data:{"conversationId":1,"messageId":2,"message":{"id":2,"conversationId":1,"role":"ASSISTANT","content":"行程建议","modelName":"configured-model","createTime":"2026-10-16T10:01:00","feedback":null,"feedbackAt":null}}
```

开始流前的认证、归属、参数及状态错误使用普通 HTTP JSON。开始流后的模型异常、超时、非正常结束或回复入库失败发送以下事件并结束流：

```text
event:error
data:{"success":false,"message":"Agent response could not be completed","errorCode":"AGENT_FAILED","data":null}
```

失败保留已提交 USER，不把错误文本或未完成片段写成 ASSISTANT。delta 只是暂时展示，失败后历史可能无法恢复这些片段，前端应以 complete 中的消息及历史查询为准。连接断开后可能无法收到错误事件；服务会中断模型订阅并释放协调锁。模型最长等待 3 分钟，SSE 超时为 4 分钟。

同一会话的 Agent 调用在单 JVM 内串行，处理期间改名/归档/关闭返回 409 `CONVERSATION_BUSY`。协调锁在完成或异常时释放，模型执行期间不持有数据库事务。此协调不覆盖多实例部署，多个实例共享相同运行 session 需要后续引入统一协调。

相同正文再次发送是独立消息，可能重复调用 Agent；不支持客户端幂等键或跨重试去重。网络失败后应先查询历史再决定重发。业务历史不替代 AgentScope 运行时状态；本次不提供逐请求审计或持久化调用状态。

## 验证方式

`ConversationIntegrationTest` 默认使用独立 H2 MySQL 模式数据库及随机 HTTP 端口，模拟 Agent，不发起付费模型调用；覆盖 MockMvc、真实 HTTP、事务回滚、并发追加/反馈、SSE 成功/失败/超时及会话协调。

真实 MySQL 验证需先在独立 `corptrip_history_verify_*` 数据库初始化完整 SQL，设置 `CORPTRIP_TEST_DB_URL`、`CORPTRIP_TEST_DB_USER`、`CORPTRIP_TEST_DB_PASSWORD` 后运行测试。测试拒绝其他数据库名；MySQL 模式禁用 Spring SQL 初始化并默认使用 8080，运行前需释放该端口。数据库回归可用 `-Dcorptrip.test.http-port=0` 分配临时端口，8080 启动验收仍需单独执行。测试会清理验证库内会话/消息并注册测试用户，禁止指向业务数据库。

### 2026-10-16 验证记录

- Java 21、指定 Maven 3.9.12：`mvn test`、`mvn -DskipTests compile`、`mvn clean test`、`mvn clean package` 均为 BUILD SUCCESS。全量 39 项测试通过，新增会话/消息 17 项。
- MySQL 8.0.26 独立验证库：精确列清单、时间精度、NULL 默认值、主键、session 唯一索引及两类会话列表/消息索引均已核验；两表无物理外键。新环境初始化 SQL 与独立迁移的新表定义完全一致。
- 真实 MySQL 全量 `clean package` 通过（39 项、零失败/错误），应用在临时 HTTP 端口启动；真实 HTTP 覆盖 SSE 写入、历史分页、双会话排序、改名、反馈切换/取消、归档/关闭及越权。模型使用模拟网关，无付费调用。
- 原 `corptrip` 的 `t_config`、`agentscope_sessions` 的 SHOW CREATE TABLE 与初始快照完全一致，未对原库执行本次迁移；Spring SQL 初始化在 MySQL 验证模式为 never，业务新增代码无启动 DDL。
- 8080 验收待该端口的现有 IDE 应用退出后执行。

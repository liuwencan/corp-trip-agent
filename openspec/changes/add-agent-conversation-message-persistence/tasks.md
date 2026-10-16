# Tasks

## 1. 数据库与部署

- [x] 1.1 只读核验目标库 t_user、AgentScope 表和新表命名冲突，按 design.md 精确字段更新 sql/init.sql 并新增独立 V<date>__agent_conversation_message.sql；在隔离 MySQL 验证两张新表的 SHOW CREATE TABLE、默认值、查询索引及 runtime_session_id 唯一索引，确认既有表不变。
- [x] 1.2 验证时间精度、feedback/feedback_at 默认 NULL、主键和逻辑关联；确认消息表只有设计清单字段，会话只保留清单中的状态/链路字段，不再生成已删除列。
- [x] 1.3 在 doc/agent-conversation-history.md 写明结构前置核验、备份、先会话后消息的迁移顺序、停止写入及数据导出回滚方式，注明 MySQL DDL 不能依赖事务回滚和应用启动不执行 DDL；核对文档与脚本一致。

## 2. 领域对象与事务持久化

- [x] 2.1 按精简设计新增 Lombok Entity、Mapper、会话状态/角色/反馈枚举及错误码，主键用于 API 引用；编译并核对映射，确认 feedback 和 feedback_at 可显式更新为 NULL。
- [x] 2.2 实现按当前用户查询会话与消息、会话行锁及消息追加事务：插入消息、递增计数、显式刷新会话 update_time 同事务完成；数据库集成测试覆盖并发计数和任一写入失败时整体回滚。
- [x] 2.3 新增创建/更新会话、发送消息、分页及反馈 Form/VO；表单测试覆盖正整数 ID、标题/正文长度、分页边界、会话状态、feedback 字段缺失与显式 NULL、非法值，确认客户端不能覆盖归属及服务端时间。
- [x] 2.4 服务测试覆盖所有权隔离、会话归档/关闭、非法重新激活、消息按 create_time/id 排序、重复文本作为独立消息、created_by 来源；核对返回 VO 不包含删除字段。

## 3. 历史接口与侧边栏排序

- [x] 3.1 实现会话创建、列表、详情、改名和状态修改接口，使用 CurrentUser 和 Response/PageResponse；MockMvc 测试覆盖匿名、跨用户、非法状态及正确分页，验证 update_time DESC/id DESC 排序。
- [x] 3.2 实现消息分页查询，按 create_time ASC/id ASC 稳定排列；测试覆盖同时间多消息、越页、空结果及会话/消息归属，返回当前反馈和反馈时间。
- [x] 3.3 验证新增 USER/ASSISTANT 消息均更新会话时间和计数，改名/状态变更刷新时间而反馈不影响侧边栏；集成测试确认不存在覆盖旧计数或旧时间的问题。
- [x] 3.4 在 doc/agent-conversation-history.md 记录主键路径、请求响应、会话状态与筛选、分页限制、时间排序及重复提交语义；核对示例与实际校验及响应一致。

## 4. 点赞点踩

- [x] 4.1 实现 PUT /agent/conversations/{conversationId}/messages/{messageId}/feedback，校验当前用户、会话/消息关联和 ASSISTANT 角色；Web/服务测试覆盖本人、其他用户、消息不在会话及非 ASSISTANT。
- [x] 4.2 实现 LIKE/DISLIKE/NULL 及 feedback_at 的原子更新，重复相同反馈保留时间，切换刷新时间，取消两字段置 NULL；数据库测试覆盖 MyBatis-Plus NULL 写入、并发更新、已归档/关闭历史反馈和会话更新时间不变。
- [x] 4.3 补充反馈接口文档，说明显式 NULL、字段缺失错误、服务端时间、仅保留当前反馈；核对查询 VO 及示例与实现一致。

## 5. Agent 接入

- [x] 5.1 将现有聊天入口的固定用户 ID 替换为 CurrentUser，按 conversationId 校验并加载 runtime_session_id，避免接受客户端任意 session 覆盖；测试验证匿名/跨用户拒绝及继续会话上下文映射。
- [x] 5.2 在事务外调用 Agent，先持久化 USER，完整回复入库后确认完成；测试覆盖模型失败、中断及回复持久化失败，确保保留已提交消息、不写虚构回复、未成功入库不宣告完成。
- [x] 5.3 接好现有 SSE 订阅、消息/会话 ID 与错误/完成事件，清理重复调用路径；实现单 JVM 同会话调用串行化及归档/关闭协调，测试并发调用、异常释放和处理期间状态变更，不引入跨重试去重承诺。
- [x] 5.4 在 doc/agent-conversation-history.md 补充 SSE 事件、错误处理、未完成片段限制、重复提交和单实例边界；用模拟 Agent 进行端到端验证，无需真实付费模型请求。

## 6. 构建与整体验收

- [x] 6.1 使用指定 Java 21/Maven 路径执行 mvn test、mvn -DskipTests compile、mvn clean test、mvn clean package；所有命令 BUILD SUCCESS 且新增测试实际执行。
- [ ] 6.2 在隔离 MySQL 启动应用并确认 8080 监听，端到端验证创建、多会话排序、消息分页、改名、反馈切换/取消、归档/关闭和越权错误；保存脱敏结果，确认启动无自动 DDL。
- [x] 6.3 执行 openspec validate add-agent-conversation-message-persistence --type change --strict，检查 git diff/status 和敏感信息；确认代码、SQL、文档、测试与精简字段一致，无本地配置、日志、构建产物或无关变更。

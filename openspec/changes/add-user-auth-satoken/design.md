# Design

## Context

动机见 `proposal.md`。项目是 Java 21、Spring Boot 3.5.6 单模块 Maven 后端，已使用 Spring MVC、Jakarta Validation、MyBatis-Plus 和 MySQL，响应为 `Response` / `PageResponse`。当前未引入鉴权依赖，也没有全局异常处理器和用户表。

已检查源码、初始化 SQL、测试及设计文档：

- `AdminConfigController` 位于 `/config`，其所有操作均无登录或角色校验；动态配置有本机缓存，新增鉴权不得改变现有缓存更新逻辑。
- `PromptController` 位于 `/system/prompt`；`PromptService` 通过 `OperatorIdentityProvider` 读取操作人，默认实现只读取 Servlet Principal，不能直接获得 Sa-Token 身份。
- Prompt 操作人字段为 `varchar(64)`，可保存用户 bigint ID 的十进制字符串，无需改表；历史操作人字符串保持原样。
- `sql/init.sql` 只有配置和 Prompt 相关表；没有实际查询本地数据库结构，本设计不声称已经核验运行数据库。
- 默认配置启用了 `/druid/*` 监控 Servlet，该 Servlet 不受 MVC 拦截器保护。
- AgentScope Harness 已作为依赖存在，但当前源码没有 Agent 聊天或会话 Controller；设计文档中的多组织、多角色及会话归属为后续目标。
- 已有 JUnit / Mockito 的 Prompt 服务和表单校验测试，可复用测试依赖。

## Goals / Non-Goals

**Goals:**

- 使用项目现有分层和响应结构，交付能独立验证的账户、会话和两种角色访问边界。
- 使用服务端可信用户 ID，补齐现有 Prompt 身份桥接，不重写其业务状态机。
- 将密码持久化与会话失效协调，防止重置之后并发旧密码登录恢复有效会话。

**Non-Goals:**

- 不建设通用 RBAC 表、组织权限系统或 Spring Security 过滤器链。
- 不新增 Agent 会话和工具功能，不声称当前登录校验已解决未来会话资源归属问题。
- 本版仅支持单 JVM 内存 Token，会话不跨重启或多实例同步。

## Decisions

### 1. 模块和依赖

新增 `cn.lwccxy.trip.system.user`，按 `controller`、`service`、`mapper`、`domain.entity`、`domain.form`、`domain.vo`、`enums` 分层。账户服务负责密码、事务、身份和查询；Controller 仅处理校验和响应。`common.config` 放 Sa-Token 接入配置，`common.exception` 放统一异常处理，`common.auth` 提供当前用户读取能力。

`pom.xml` 引入 `cn.dev33:sa-token-spring-boot3-starter`，使用支持 Boot 3 的稳定版本并显式锁定版本；编码使用 Boot BOM 管理版本的 `org.springframework.security:spring-security-crypto` 的 `BCryptPasswordEncoder`，初始 cost 为 10。实施时核验选定 Starter 的 Java 21 / Boot 3.5.6 启动兼容性及 API；不混用 Boot 2 Starter。

选择 Sa-Token 原生会话而非自行实现 JWT 签发，因为用户已经指定鉴权框架且需要退出、改密后撤销会话。仅引入 crypto 库而非完整 security starter，可避免引入另一套自动配置的认证入口。

### 2. 表结构与最小角色模型

新表 `t_user` 使用 InnoDB、utf8mb4，字段如下；独立迁移与初始化定义必须一致。

| 字段 | 类型与约束 | 用途 |
| --- | --- | --- |
| `id` | bigint，自增主键 | Sa-Token loginId |
| `username` | varchar(32)，非空、唯一索引，ASCII binary 排序规则 | 规范化后的小写用户名 |
| `password_hash` | varchar(100)，非空 | BCrypt 哈希 |
| `role` | varchar(16)，非空，默认 USER | USER / ADMIN，服务端校验枚举 |
| `must_change_password` | tinyint(1)，非空，默认 0 | 临时密码使用限制 |
| `credential_version` | bigint，非空，默认 0 | 密码或受控角色变更使旧会话失效 |
| `create_time` | datetime，非空，默认当前时间 | 创建时间 |
| `update_time` | datetime，非空，自动更新 | 更新时间 |

新表 `t_user_password_reset_log`：`id` bigint 自增主键、`target_user_id` bigint 非空、`operator_id` bigint 非空、`reason` varchar(1000) 非空、`create_time` datetime 默认当前时间，增加 `(target_user_id, create_time)` 索引。仅记录成功的管理员重置，审计写入与凭据变更同事务；非法请求经错误响应体现，不扩大到通用审计平台。理由不得包含临时密码或完整敏感身份信息。

第一版一个用户一个角色，无角色表、关联表、删除标记或账户管理状态。公开注册始终写 `USER`；首次用户也不例外。角色变更仅由受控运维操作完成。用户查询 VO 不暴露 `password_hash`、`credential_version`，不直接返回 Entity。

密码处理选择自适应加盐单向哈希而非可逆加密。用户名统一 `trim + Locale.ROOT 小写` 后校验 `[a-z0-9_]{3,32}`。密码不 trim，8–64 字符且 UTF-8 不超过 72 字节，字节校验避免 BCrypt 输入截断；以 `matches` 判定当前/新密码是否相同。注册 DTO 显式拒绝 `role`、`id`、`mustChangePassword` 等受保护扩展字段，不全局改变其他模块未知字段策略。并发重复由数据库唯一索引兜底，将用户名唯一冲突转换为 `USER_ALREADY_EXISTS`。

### 3. 接口契约

| 方法与路径 | 请求 | 权限 | 成功响应 data |
| --- | --- | --- | --- |
| `POST /auth/register` | username、password | 匿名 | UserVO，不自动登录 |
| `POST /auth/login` | username、password | 匿名 | tokenName、tokenValue、expiresIn、UserVO |
| `POST /auth/logout` | 无 | 已登录，包括必须改密用户 | 空，退出当前 Token |
| `GET /auth/me` | 无 | 已登录，包括必须改密用户 | UserVO |
| `PUT /auth/password` | oldPassword、newPassword | 已登录，包括必须改密用户 | 空，成功后重新登录 |
| `POST /system/user/query` | pageNo=1、pageSize=10、keyword 可选、role 可选 | ADMIN，已完成必须改密 | PageResponse<UserVO> |
| `POST /system/user/{id}/reset-password` | temporaryPassword、reason | ADMIN，已完成必须改密 | 空，不回显临时密码 |

所有 Form 使用 Jakarta Validation，路径 ID 校验大于 0，分页为 pageNo≥1、pageSize 1–200，keyword 最长 32 字符，重置说明去空白后非空且最长 1000。查询用户名关键字按注册同样方式规范化，按 `create_time desc, id desc` 稳定排序；可选角色只接受枚举。

本人改密目标来自当前身份，不存在按客户端 userId 改密的入口。未知用户名与错误密码统一消息，未知用户名也执行固定虚拟 BCrypt 哈希匹配，减小明显的校验耗时差异，不查询或记录明文。

管理员在线下确认本人及账户归属，再通过接口设置临时密码并通过可信线下渠道通知。第一版不提供匿名重置请求工单或邮件短信发送，忘记密码的用户需要联系管理员。重置自身也按同样规则使当前 Token 失效，下一次登录进入必须改密状态。

### 4. 会话、改密与并发处理

配置 Sa-Token 仅从 `satoken` 请求头读取 Token，关闭 Cookie、请求体及 URL 参数读取。默认 timeout=86400 秒，允许多个并发登录但关闭 Token 共享，使每次登录有独立 Token；退出当前 Token，不退出其他设备。

登录读取并验证账户快照，创建 Sa-Token 登录态时把快照中的 `credential_version` 写入对应的 **Token session**，不能只保存到用户级共享 session。每次受保护请求校验 Token、查询当前账户并比较版本；账户不存在、版本缺失或不一致均按 `AUTH_NOT_LOGIN` 拒绝。请求内复用当前用户读取结果，角色与必须改密标志读取服务端数据，不建立额外角色缓存。

改密和重置在事务内按用户 ID 行锁串行处理：锁定用户、验证最新当前密码或新旧不同、更新哈希、设置/清除必须改密标志、递增 `credential_version`；管理员重置同步插入审计。通过事务提交后回调调用 Sa-Token 按 loginId 退出所有 Token，失败事务不退出会话。只使用数据库提交前踢出会话会导致事务失败仍影响用户，故不采用。

数据库版本校验是失效保证，批量退出负责清理运行时会话。若旧密码登录先验证后与重置交错，其会话版本仍来自旧快照，后续鉴权失败；批量退出偶发失败也无法让旧版本继续访问。重置不会中止提交前已经进入业务处理的请求，失效保证针对提交后的下一次鉴权。

### 5. 鉴权边界与可信身份

Sa-Token MVC 拦截器默认保护业务 Handler，仅精确放行 `POST /auth/register`、`POST /auth/login`。不能使用 `/auth/**` 放行。CORS 只放行合法预检，不主动引入通配跨域策略；错误分派按 DispatcherType 处理，不将普通请求 `/error` 当作业务放行路径。

校验顺序为登录和凭据版本 → 必须改密限制 → 管理角色 → 业务校验。必须改密用户只允许 `GET /auth/me`、`PUT /auth/password`、`POST /auth/logout`，其他业务返回 `PASSWORD_CHANGE_REQUIRED`，即使角色为 ADMIN 也受限。

`StpInterface` 提供服务端角色读取，管理 Controller 使用 `@SaCheckRole("ADMIN")` 并确保注解检查生效；路径管理规则作为集中边界覆盖 `/config`、`/config/**`、`/system/prompt`、`/system/prompt/**`、`/system/user` 和 `/system/user/**`，测试两种层级不会漏掉根路径。未来 Agent Controller 自动受登录保护，资源归属应在未来业务服务另行校验。

保留 `OperatorIdentityProvider` 接口，替换默认实现为读取当前 Sa-Token 用户 ID 的十进制字符串；没有 HTTP 登录上下文时返回 null，由现有服务的操作人缺失规则处理。所有 Prompt 创建/更新/发布记录使用该身份，历史字符串不迁移。当前用户能力同样可供后续 Agent 工具服务读取，不依赖请求体或自然语言声称的身份。

在默认及现有环境配置中关闭 Druid stat-view-servlet，确保没有 profile 重新开启；MVC 拦截器无法保护独立 Servlet，若未来确实需要监控，单独设计过滤器/网络边界再开启。

### 6. 错误响应和既有兼容

统一异常处理器将 Sa-Token 未登录异常映射为 HTTP 401 / `AUTH_NOT_LOGIN`，角色不足为 HTTP 403 / `AUTH_FORBIDDEN`，必须改密为 HTTP 403 / `PASSWORD_CHANGE_REQUIRED`；参数校验、JSON 解码和受保护字段注入为 HTTP 400 / `INVALID_PARAMETER`。新增业务错误使用 `BaseExceptionInterface` / `BizException` 和现有 `Response.fail`，避免另一套响应模型。

| 错误码 | HTTP 状态 | 场景 |
| --- | --- | --- |
| `AUTH_INVALID_CREDENTIALS` | 401 | 登录错误或本人改密旧密码错误 |
| `USER_ALREADY_EXISTS` | 409 | 规范化用户名重复 |
| `USER_NOT_FOUND` | 404 | 管理员重置目标不存在 |
| `PASSWORD_UNCHANGED` | 400 | 新密码或临时密码等于当前密码 |

不改写已有 Config / Prompt 服务自行返回的业务失败响应状态和错误码。未预期异常返回 HTTP 500 的无敏感信息统一失败响应，日志不输出完整请求、Entity、密码、哈希或 Token。Web 层测试检查拦截器抛出的异常同样经过统一响应处理。

## Risks / Trade-offs

- [单实例内存会话重启即失效，多实例无法共享] → 本次明确仅单实例；扩大部署前单独引入 Sa-Token Redis DAO 等共享会话方案。
- [现有后台客户端匿名调用会失败] → 标注 breaking change，在维护窗口先迁移数据库、部署鉴权版本并设置管理员，再恢复客户端访问并携带 Token。
- [用户名体系不能自动证明人员身份] → 找回依赖管理员线下核验，记录非敏感核验说明；自动化恢复留待引入额外可信渠道。
- [增加每次请求账户查询] → 第一版优先使用最新角色、改密标志和凭据版本；不引入额外缓存一致性机制。
- [数据库和内存会话不在同一事务] → 使用数据库版本失效保证及提交后会话清理；会话清理失败仅记录非敏感诊断，不回报数据库已回滚。
- [公开注册登录缺少完整抗滥用策略] → 第一版定位企业内部部署；验证码、专门限流和组织邀请机制不纳入本变更。

## Migration Plan

1. 实施阶段先只读核验实际 `corptrip` 表结构、现有迁移及备份情况，确认新增表没有冲突；不执行自动 DDL 或覆盖已有未提交迁移。
2. 为新环境在 `sql/init.sql` 追加两张新表；为已存在环境提供 `sql/migration/V20261014__user_auth_satoken.sql`。现有 Prompt 迁移与此新增表迁移独立，文档按现有环境适用情况列明执行顺序，不强制重复执行无关迁移。
3. 文档要求执行前备份数据库并记录执行结果。只执行适用于该环境的迁移或全新初始化，不重复运行建表；应用启动不建表、不 ALTER、不删除数据。
4. 启动新版，在内网调用注册创建预定管理员候选账户；运维通过参数化/明确目标的手工 SQL 事务按 ID 和规范化用户名核对唯一记录，更新角色为 ADMIN 并递增 credential_version，检查恰好一行受影响，然后重新登录。文档不内置真实密码、Token 或固定管理员账号，提升操作不自动由应用执行。
5. 用户名、密码注册、普通/管理员角色、后台既有行为、本人改密、管理员重置及其 Token 失效完成端到端验证。确认 `/druid/*` 没有诊断入口。
6. 回滚应用会恢复旧版本没有鉴权的访问行为，必须在受控维护/网络限制环境执行。新表先保留供再次上线；如需回滚表结构，停应用、备份新表，再按单独说明先移除重置审计表、后移除用户表，明确账户与审计数据损失且不得影响现有 Config / Prompt 表。

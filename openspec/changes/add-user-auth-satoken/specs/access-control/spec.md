# access-control Spec Delta

## Purpose

为 CorpTripAgent 的应用接口提供一致的登录与角色访问边界，将可信登录身份用于后台操作和审计，确保匿名请求、普通用户越权以及必须改密账户的请求均得到可预测的拒绝结果，为后续 Agent 业务复用用户身份建立基础。

## ADDED Requirements

### Requirement: Application endpoints require authenticated identity by default

系统 SHALL 默认要求应用业务接口携带有效的 `satoken` 请求头，只有精确匹配的注册和登录接口允许匿名访问。系统 SHALL 仅对框架实际执行的错误分派及合法 CORS 预检做技术放行，不得将整个认证路径前缀或普通 `/error` 请求作为业务鉴权豁免。缺失、伪造、过期、退出或凭据版本失效的 Token SHALL 返回 HTTP 401 和 `AUTH_NOT_LOGIN`。

#### Scenario: Reject anonymous access to existing management endpoints

- **WHEN** 匿名调用者请求 `/config/query` 或 `/system/prompt/query`
- **THEN** 系统返回 HTTP 401 和 `AUTH_NOT_LOGIN`，不执行查询或变更

#### Scenario: Allow only explicit anonymous authentication actions

- **WHEN** 匿名调用者请求注册、登录或同一前缀下的本人信息、退出、改密接口
- **THEN** 仅注册和登录请求可进入业务处理，其余请求被认证规则拒绝

#### Scenario: Reject tokens from query parameters or request bodies

- **WHEN** 调用者仅在 URL 参数、Cookie 或请求体中提交 Token 而不携带 `satoken` 请求头
- **THEN** 系统按未登录处理，不从其他来源恢复登录态

### Requirement: Fixed roles protect management operations

系统 SHALL 支持单账户固定角色 `USER` 和 `ADMIN`。`/config/**`、`/system/prompt/**`、`/system/user/**` 的全部读写接口 SHALL 要求 `ADMIN`，已登录普通用户访问 SHALL 返回 HTTP 403 和 `AUTH_FORBIDDEN`。角色 SHALL 以服务端账户数据为准，不接受请求参数或自声明请求头作为身份、角色或权限依据。

#### Scenario: Ordinary user cannot access management data

- **WHEN** 普通用户使用有效 Token 查询配置、Prompt 历史或用户列表，或尝试密码重置、配置修改和 Prompt 发布
- **THEN** 系统返回 HTTP 403 和 `AUTH_FORBIDDEN`，不返回管理数据或执行操作

#### Scenario: Administrator can manage existing modules

- **WHEN** 无必须改密限制的管理员使用有效 Token 请求配置或 Prompt 管理接口
- **THEN** 系统允许请求进入既有业务逻辑，并保留原成功数据和业务错误约定

#### Scenario: Ignore forged identity and role headers

- **WHEN** 普通用户在请求头或请求体中伪造管理员角色或他人用户 ID
- **THEN** 系统仍按 Token 所属账户的服务端角色执行校验，不提升权限

### Requirement: Administrators can locate users for recovery

系统 SHALL 提供仅管理员可用的用户分页查询，支持用户名关键字和 `USER`/`ADMIN` 角色筛选；页码 SHALL 大于等于 1，页大小 SHALL 为 1–200。结果 SHALL 使用项目分页响应结构，包含用户 ID、用户名、角色、必须改密标志和创建、更新时间，且分页总数和总页数与筛选结果一致。

#### Scenario: Query filtered user page

- **WHEN** 管理员以合法页码、页大小、用户名关键字和角色查询
- **THEN** 系统返回匹配用户的稳定排序分页及正确的页码、页大小、总数和总页数

#### Scenario: Reject invalid pagination or role

- **WHEN** 管理员提交小于 1 的页码、超过 200 的页大小或未知角色筛选值
- **THEN** 系统返回 HTTP 400 和 `INVALID_PARAMETER`，不执行无界查询

### Requirement: Administrator provisioning cannot be triggered by public registration

系统 MUST NOT 通过首个注册用户自动成为管理员、公开角色分配接口或内置通用管理员密码创建管理员。第一版 SHALL 支持由运维在受控数据库操作中，将已注册的指定账户提升为 `ADMIN`，并通过重新登录获得管理权限。

#### Scenario: Initial registration remains ordinary

- **WHEN** 空用户数据库收到第一次成功注册
- **THEN** 新账户仍为 `USER`，不能调用管理接口

#### Scenario: Provision first administrator through controlled operation

- **WHEN** 运维按部署文档将明确选定的已注册账户提升为 `ADMIN` 并要求重新登录
- **THEN** 该账户的新登录态可以访问管理接口，未选定账户仍无管理权限

### Requirement: Prompt audit identity comes from the authenticated user

系统 SHALL 将当前已认证账户的稳定用户 ID 用作 Prompt 创建、修改、发布、下线和回滚操作的操作人标识，不接受请求中的替代身份，不修改既有历史审计数据。缺少可信身份时 MUST NOT 使用固定管理员或系统用户兜底执行 HTTP 管理操作。

#### Scenario: Audit administrator action

- **WHEN** 管理员使用有效 Token 发布 Prompt 版本，并在请求中伪造其他操作人信息
- **THEN** 新操作记录及相关操作人字段使用 Token 对应用户 ID，既有历史记录保持不变

### Requirement: Authentication and validation failures use the common response envelope

系统 SHALL 使用包含 `success=false`、`message`、`errorCode` 和空 `data` 的项目统一响应返回可预期的认证、授权和请求校验错误；未登录或凭据错误 SHALL 使用 HTTP 401，无权限或必须改密 SHALL 使用 HTTP 403，参数错误 SHALL 使用 HTTP 400。响应 MUST NOT 暴露堆栈、SQL、凭据或 Token 内容，且不得覆盖既有模块自行返回的业务错误码。

#### Scenario: Return consistent errors across boundaries

- **WHEN** 请求触发拦截器认证失败、控制器权限失败或表单校验失败
- **THEN** 系统返回对应 HTTP 状态与相同结构的失败响应，可由调用方统一解析

#### Scenario: Error dispatch does not leak authentication internals

- **WHEN** 框架处理业务异常并执行错误分派
- **THEN** 系统不因重复鉴权陷入递归错误，也不返回认证内部对象或敏感诊断信息

### Requirement: Independent diagnostic servlet is not publicly exposed

系统 SHALL 在默认交付配置中关闭不经过应用 MVC 鉴权链的 Druid 监控 Servlet，避免匿名调用者经诊断入口读取数据源、SQL 或运行信息。

#### Scenario: Druid monitor is unavailable by default

- **WHEN** 调用者在默认部署中请求 `/druid/` 及其诊断子路径
- **THEN** 系统不提供 Druid 监控页面或监控数据

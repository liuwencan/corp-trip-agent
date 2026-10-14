# user-auth Spec Delta

## Purpose

为 CorpTripAgent 提供简单、可验证的用户名密码账户体系，覆盖普通用户注册、登录态、本人改密和管理员辅助密码找回，以安全存储的凭据和明确的会话失效行为支撑后续业务身份读取。

## ADDED Requirements

### Requirement: Users can register ordinary accounts

系统 SHALL 接受用户名、密码创建 `USER` 账户，返回用户 ID、规范化用户名、角色和必须改密标志。用户名 SHALL 去除首尾空白后转为小写，规范化结果为 3–32 位 ASCII 字母、数字或下划线，且全局唯一。密码 SHALL 为 8–64 个字符且 UTF-8 编码不超过 72 字节，不得修剪或截断。注册 MUST NOT 接受客户端角色、用户 ID 或必须改密标志覆盖。

#### Scenario: Register a valid user

- **WHEN** 匿名调用者使用未占用的用户名 ` Alice_1 ` 和合法密码注册
- **THEN** 系统创建用户名 `alice_1`、角色 `USER`、必须改密标志为 false 的账户，返回安全用户信息但不自动登录

#### Scenario: Reject concurrent duplicate registration

- **WHEN** 两个请求并发注册规范化后相同的用户名
- **THEN** 最多创建一个账户，其余请求返回 `USER_ALREADY_EXISTS`，不返回数据库异常详情

#### Scenario: Reject invalid credentials or role injection

- **WHEN** 注册请求含非法用户名、不符合长度或字节限制的密码，或提交 `role=ADMIN` 等受保护字段
- **THEN** 系统返回 `INVALID_PARAMETER`，不创建账户

### Requirement: Passwords are stored as salted one-way hashes

系统 SHALL 仅持久化随机加盐的自适应单向密码哈希，使用哈希匹配校验凭据。系统 MUST NOT 存储明文密码、可逆密码密文或简单未加盐摘要，也 MUST NOT 在用户 VO、密码重置审计、日志和错误响应中暴露密码、哈希或登录 Token；成功登录响应中的 Token 除外。

#### Scenario: Equal passwords have independent hashes

- **WHEN** 两个账户使用相同密码完成注册
- **THEN** 数据库保存不同的密码哈希，两个账户均能通过各自密码匹配校验

#### Scenario: Read user data without credentials

- **WHEN** 用户查询本人信息或管理员查询用户列表
- **THEN** 返回数据只包含安全账户字段，不含密码、密码哈希或其他账户的登录 Token

### Requirement: Users can log in and terminate their sessions

系统 SHALL 使用规范化用户名与原始密码验证账户，成功后返回请求头名称 `satoken`、Token、有效期和安全用户信息。Token 默认有效期 SHALL 为 86400 秒且可通过非敏感服务配置调整；系统 SHALL 支持多个独立登录 Token，退出只撤销当前 Token。未知用户名和错误密码 SHALL 使用相同的 `AUTH_INVALID_CREDENTIALS` 错误响应。

#### Scenario: Log in and read current user

- **WHEN** 用户提交正确凭据并用返回的 Token 请求当前用户接口
- **THEN** 系统返回该 Token 所属用户 ID、用户名、角色和必须改密标志

#### Scenario: Reject incorrect login without revealing existence

- **WHEN** 请求使用不存在的用户名或已存在用户名的错误密码登录
- **THEN** 系统返回 HTTP 401 和相同的 `AUTH_INVALID_CREDENTIALS` 消息，不创建登录态

#### Scenario: Logout revokes only the current token

- **WHEN** 同一用户持有两个独立 Token 并使用其中一个退出
- **THEN** 已退出 Token 的后续受保护请求返回 `AUTH_NOT_LOGIN`，另一个 Token 仍可使用

#### Scenario: Expired token cannot access account data

- **WHEN** 调用者使用超过有效期的 Token 请求本人信息
- **THEN** 系统返回 HTTP 401 和 `AUTH_NOT_LOGIN`，不返回用户数据

### Requirement: Users can change their own password

系统 SHALL 允许已登录用户凭当前密码和合法新密码修改本人密码，不接受请求中的用户 ID 来选择目标账户。错误当前密码 SHALL 返回 `AUTH_INVALID_CREDENTIALS`，与当前密码相同的新密码 SHALL 返回 `PASSWORD_UNCHANGED`。成功后系统 SHALL 清除必须改密标志并撤销该用户所有旧登录态，要求使用新密码重新登录。

#### Scenario: Change own password and revoke sessions

- **WHEN** 用户提交正确当前密码及不同的合法新密码
- **THEN** 新密码生效，旧密码不能登录，所有原有 Token 不能继续访问受保护接口

#### Scenario: Preserve password after rejected change

- **WHEN** 用户提交错误当前密码、不合法新密码或与当前密码相同的新密码
- **THEN** 系统返回对应错误，密码、必须改密标志和现有登录态保持不变

### Requirement: Administrators can assist password recovery

系统 SHALL 提供仅管理员可用的密码重置接口，接受目标用户 ID、符合密码规则的临时密码及 1–1000 字符的非空核验说明。管理员 SHALL 在调用前完成线下身份核验，并通过可信线下渠道告知临时密码。系统 SHALL 原子保存新密码哈希、必须改密标志和包含操作人 ID、目标用户 ID、说明、时间的成功审计记录，并撤销目标用户所有旧登录态。

#### Scenario: Reset password after identity verification

- **WHEN** 管理员完成身份核验后为存在的用户提交新的临时密码和说明
- **THEN** 系统完成重置和审计，返回成功且不回显密码，目标用户旧密码及旧 Token 均失效

#### Scenario: Reject unknown user or invalid reset

- **WHEN** 管理员重置不存在的用户，或提交不合法密码、空说明或与目标当前密码相同的临时密码
- **THEN** 系统分别返回 `USER_NOT_FOUND`、`INVALID_PARAMETER` 或 `PASSWORD_UNCHANGED`，不修改凭据或产生成功审计

#### Scenario: Roll back reset when audit persistence fails

- **WHEN** 密码重置过程中审计记录无法持久化
- **THEN** 整个数据库事务回滚，旧密码、必须改密标志和原有登录态保持不变

#### Scenario: Public username cannot reset a password

- **WHEN** 匿名调用者尝试只用用户名或用户 ID 重置密码
- **THEN** 系统不提供可用的匿名重置通道，不改变任何账户凭据

### Requirement: Temporary passwords require replacement

系统 SHALL 在管理员重置后允许目标用户使用临时密码登录，但该用户在完成本人改密前 SHALL 仅能查询本人信息、修改本人密码和退出。即使目标账户拥有管理员角色，也 MUST NOT 在必须改密状态下访问管理或其他业务接口。

#### Scenario: Restrict temporary-password session

- **WHEN** 被重置密码的用户登录并访问配置、Prompt 或其他业务接口
- **THEN** 系统返回 HTTP 403 和 `PASSWORD_CHANGE_REQUIRED`，本人信息、改密和退出仍可使用

#### Scenario: Unlock after replacing temporary password

- **WHEN** 用户提交正确临时密码完成本人改密并重新登录
- **THEN** 必须改密标志为 false，系统按该用户角色恢复正常访问边界

### Requirement: Credential updates invalidate racing old sessions

系统 SHALL 确保密码修改或重置事务提交后，凭旧密码校验结果创建的登录态也不能通过后续鉴权；并发密码写入 SHALL 保持串行的有效凭据结果。事务失败 MUST NOT 使现有有效登录态失效。

#### Scenario: Login races with password reset

- **WHEN** 旧密码登录已通过密码校验，但管理员重置事务在其登录态创建前提交
- **THEN** 该旧凭据请求即使获得 Token，Token 也不能访问受保护接口

#### Scenario: Concurrent credential writes preserve current-password validation

- **WHEN** 本人改密和管理员重置并发执行
- **THEN** 系统按账户串行校验与写入，本人改密不能基于已经失效的当前密码覆盖新凭据

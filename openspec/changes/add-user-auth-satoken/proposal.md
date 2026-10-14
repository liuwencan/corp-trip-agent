# Proposal

## Why

CorpTripAgent 当前没有用户账户和登录鉴权，配置接口可被直接访问，Prompt 管理操作也缺少统一可信身份。先建立简单的用户名、密码账户体系，为当前后台和后续 Agent 业务提供可复用的登录用户身份。

## What Changes

- 支持用户名、密码注册及登录、退出、查询当前用户和修改本人密码；注册只能创建普通用户。
- 密码采用 BCrypt 加盐单向哈希存储，接口和日志不暴露密码及密码哈希。
- 支持 `USER`、`ADMIN` 两种固定角色，每个用户一个角色；首个管理员通过受控数据库操作提升已注册账户，不内置默认密码或公开提权接口。
- 按用户确认的方案提供管理员辅助找回密码：管理员在线下核验身份后设置临时密码并记录原因，用户重新登录后必须修改密码；不接入邮箱、短信或公开的用户名直接重置接口。
- 接入适配 Spring Boot 3 的 Sa-Token，以请求头 Token 建立登录态；第一版使用单实例内存会话，密码修改或重置后撤销该用户原有登录态。
- **BREAKING**：除注册、登录及框架错误分派外，应用业务接口默认要求登录；现有 `/config/**`、`/system/prompt/**` 和新增用户管理接口要求管理员角色。
- 将现有 Prompt 操作人身份提供器接入 Sa-Token 用户 ID，保持原审计和版本管理语义。
- 提供管理员用户分页查询和密码重置接口，并统一参数、认证和授权异常的 `Response` 返回格式；默认关闭独立 Druid 监控 Servlet，避免绕过 MVC 鉴权。

## Capabilities

### New Capabilities

- `user-auth`: 用户名密码注册、密码安全存储、登录会话、本人改密和管理员辅助密码找回。
- `access-control`: Sa-Token 登录校验、普通用户/管理员角色边界、管理接口保护及可信操作人身份。

### Modified Capabilities

无。已有 `prompt-version-management` 的授权管理人员和操作人审计要求由新的鉴权能力落实，版本生命周期、发布与回滚规则保持既有要求。

## Impact

- 在 `system/user` 增加 Entity、Form、VO、Mapper、Service、用户认证及管理 Controller；在 `common` 增加 Sa-Token 配置和统一异常处理。
- 修改 `pom.xml` 引入 Sa-Token Spring Boot 3 Starter 和独立 BCrypt 编码依赖；不引入完整 Spring Security 过滤器链。
- `sql/init.sql` 和独立迁移增加用户表及密码重置审计表，不改变 `t_config` 或 Prompt 表；迁移文档说明前置结构核验、备份、执行顺序、管理员提升和回滚。
- 更新现有管理 Controller 的权限保护、Prompt 身份提供器及非敏感鉴权配置，添加服务/Web 测试和启动、接口验证说明。
- 当前源码尚无 Agent 会话 Controller；本次提供可复用的当前用户读取能力和默认保护策略，不新增聊天、会话归属或差旅业务功能。
- 暂不实现多角色/权限菜单、组织体系、SSO、邮件短信、自助恢复码、用户删除和在线角色分配、Redis 会话或前端页面。

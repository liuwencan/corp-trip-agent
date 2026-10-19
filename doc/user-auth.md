# 用户认证与鉴权部署说明

## 范围

认证接口使用 Sa-Token，当前配置要求请求头 `Authorization: Bearer <tokenValue>`。请求头名称和前缀分别由 `sa-token.token-name`、`sa-token.token-prefix` 决定，登录响应会返回实际的 `tokenName` 和 `tokenPrefix`。匿名接口只有 `POST /auth/register` 和 `POST /auth/login`；其他应用接口需要登录。`USER` 不能访问 `/config/**`、`/system/prompt/**` 和 `/system/user/**`，这些接口需要 `ADMIN`。

密码使用 BCrypt 哈希保存，不记录明文密码、临时密码、Token 或哈希。用户名去首尾空白并转为小写，规则为 3-32 位 ASCII 字母、数字或下划线；密码为 8-64 个字符且 UTF-8 不超过 72 字节。

## 数据库迁移

执行迁移前备份 `corptrip`，并只读确认两张新表不存在。新环境执行 `sql/init.sql`；已有环境按前置版本执行 `sql/migration/V20261014__user_auth_satoken.sql`，不得重复执行，也不得由应用启动自动建表或修改结构。执行后核对两张表的结构、唯一用户名索引，并确认现有 `t_config` 和 Prompt 表未变化。

首个管理员流程：先通过注册接口创建候选账户，运维线下核对明确的用户 ID 和用户名，在受控 SQL 事务中将其 `role` 更新为 `ADMIN` 并递增 `credential_version`，确认只影响一行后重新登录。迁移不创建默认管理员、不包含默认密码。回滚应用需先进入维护窗口；如确需删除新表，先备份，再删除审计表、用户表，明确会丢失账户数据且不得影响既有业务表。

提升操作示例（将占位 ID 替换为已核验的单个账户，并先备份）：

```sql
START TRANSACTION;
UPDATE t_user
SET role = 'ADMIN', credential_version = credential_version + 1
WHERE id = 12345 AND role = 'USER';
-- 确认受影响行数为 1 后提交，否则回滚
COMMIT;
```

MySQL DDL 自动提交，迁移失败不能依赖事务回滚；应根据 `SHOW TABLES` / `SHOW CREATE TABLE` 核验已创建对象，再按备份和维护流程人工恢复。应用启动不会执行 DDL。

## 接口

- `POST /auth/register`：`{"username":"alice_1","password":"占位密码"}`，始终注册为 `USER`，不自动登录。
- `POST /auth/login`：返回 `tokenName`、`tokenPrefix`、`tokenValue`、`expiresIn` 和安全用户信息。`tokenValue` 为不含前缀的原始 Token；后续请求头的值应由非空前缀、一个空格和 Token 拼接，未配置前缀时直接使用 Token。
- `GET /auth/me`、`POST /auth/logout`、`PUT /auth/password`：当前用户信息、退出当前 Token、使用旧密码和新密码改密。
- `POST /system/user/query`：管理员分页查询用户。
- `POST /system/user/{id}/reset-password`：管理员线下核验后设置临时密码和核验说明；临时密码必须通过可信线下渠道告知，不由接口回显。

前端应从登录响应的 `data` 中保存认证信息，并在所有受保护接口请求中携带，例如 Axios：

```javascript
const { tokenName, tokenPrefix, tokenValue } = loginResponse.data.data;
api.defaults.headers.common[tokenName] = tokenPrefix
  ? `${tokenPrefix} ${tokenValue}`
  : tokenValue;
```

当前配置下，仅发送 `satoken: <tokenValue>` 或 `Authorization: <tokenValue>` 均无法通过认证。前端页面保存了登录状态并不代表后续 HTTP 请求已携带有效凭据；排查 `AUTH_NOT_LOGIN` 时先在浏览器 Network 中核对实际发出的请求头。

管理员重置会撤销目标用户所有旧 Token，并设置“必须改密”。临时密码登录后只能访问本人信息、改密和退出；改密成功后必须重新登录。忘记密码没有邮箱、短信或只凭用户名的匿名重置通道。

重置前由管理员在线下核验身份，通过可信线下渠道传递临时密码；`reason` 只写脱敏核验事实，不写临时密码或完整敏感身份信息。重置接口成功响应不回显临时密码，失败时不产生审计记录。

## 错误和限制

未登录返回 `401 AUTH_NOT_LOGIN`，凭据错误返回 `401 AUTH_INVALID_CREDENTIALS`，无权限返回 `403 AUTH_FORBIDDEN`，必须改密返回 `403 PASSWORD_CHANGE_REQUIRED`，参数错误返回 `400 INVALID_PARAMETER`。当前会话为单 JVM 内存会话，重启或多实例不会共享 Token；扩大部署前应引入 Sa-Token Redis DAO。

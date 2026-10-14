-- 前置条件：已备份 corptrip，并确认 t_user 和 t_user_password_reset_log 不存在。
-- MySQL DDL auto-commits. On failure inspect created tables and follow the documented manual rollback.
CREATE TABLE `t_user` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化用户名',
    `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt密码哈希',
    `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',
    `must_change_password` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否必须修改密码',
    `credential_version` bigint NOT NULL DEFAULT '0' COMMENT '凭据版本',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='用户账户';

CREATE TABLE `t_user_password_reset_log` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `target_user_id` bigint NOT NULL COMMENT '目标用户ID',
    `operator_id` bigint NOT NULL COMMENT '操作人ID',
    `reason` varchar(1000) NOT NULL COMMENT '核验说明',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_reset_target_time` (`target_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='管理员密码重置审计';

-- 首个管理员不在迁移中自动创建；注册 USER 后由运维按明确 ID 手工提升并递增 credential_version。

-- 前置：已执行 V20261014__user_auth_satoken.sql，t_user 存在；备份并确认下列新表不存在。
-- 顺序：先 t_conversation 后 t_message。仅新增表，不修改既有业务/AgentScope 表。
-- MySQL DDL 隐式提交。回滚先停止写入并导出数据，通常保留表，仅回退应用。
-- 确需删表时由运维显式执行：先 t_message 后 t_conversation。
CREATE TABLE `t_conversation` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id` bigint NOT NULL COMMENT '归属用户',
    `title` varchar(200) NOT NULL COMMENT '会话标题',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/CLOSED',
    `runtime_session_id` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端生成的AgentScope会话映射',
    `message_count` int NOT NULL DEFAULT 0 COMMENT '已持久化消息数',
    `trace_id` varchar(64) DEFAULT NULL COMMENT '会话级最近链路标识',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_runtime_session` (`runtime_session_id`),
    KEY `idx_conversation_user_time` (`user_id`, `update_time`, `id`),
    KEY `idx_conversation_user_status_time` (`user_id`, `status`, `update_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='用户Agent会话';

CREATE TABLE `t_message` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` bigint NOT NULL COMMENT '所属会话',
    `user_id` bigint NOT NULL COMMENT '归属用户，与会话一致',
    `role` varchar(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM/TOOL',
    `content` longtext NOT NULL COMMENT '消息正文',
    `model_name` varchar(128) DEFAULT NULL COMMENT '模型名称',
    `created_by` bigint NOT NULL COMMENT '发起操作的认证用户',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `feedback` varchar(16) DEFAULT NULL COMMENT '用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈',
    `feedback_at` datetime DEFAULT NULL COMMENT '反馈时间',
    PRIMARY KEY (`id`),
    KEY `idx_message_conversation_time` (`conversation_id`, `create_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='Agent会话消息';

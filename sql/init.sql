CREATE TABLE `t_config` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '参数名字',
    `config_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '参数key',
    `config_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '上次修改时间',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='系统配置';

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

CREATE TABLE `t_prompt` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `prompt_key` varchar(128) NOT NULL COMMENT 'Prompt业务唯一标识',
    `prompt_name` varchar(255) NOT NULL COMMENT 'Prompt名称',
    `agent_capability` varchar(128) NOT NULL COMMENT '所属Agent能力',
    `description` varchar(1000) DEFAULT NULL COMMENT '用途说明',
    `current_version_id` bigint DEFAULT NULL COMMENT '当前生效版本ID',
    `status` varchar(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    `created_by` varchar(64) NOT NULL COMMENT '创建人',
    `updated_by` varchar(64) NOT NULL COMMENT '最后修改人',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_key` (`prompt_key`),
    KEY `idx_prompt_capability` (`agent_capability`),
    KEY `idx_prompt_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='Prompt定义';

CREATE TABLE `t_prompt_version` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `prompt_id` bigint NOT NULL COMMENT 'Prompt定义ID',
    `version_no` int NOT NULL COMMENT '同一Prompt内递增版本号',
    `prompt_content` longtext NOT NULL COMMENT '完整Prompt正文',
    `content_hash` char(64) NOT NULL COMMENT '内容SHA-256摘要',
    `version_status` varchar(32) NOT NULL DEFAULT 'DRAFT' COMMENT '版本状态',
    `change_reason` varchar(1000) NOT NULL COMMENT '修改原因',
    `created_by` varchar(64) NOT NULL COMMENT '版本创建人',
    `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '版本创建时间',
    `published_by` varchar(64) DEFAULT NULL COMMENT '最近发布人',
    `published_time` datetime DEFAULT NULL COMMENT '最近发布时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_version_no` (`prompt_id`, `version_no`),
    KEY `idx_prompt_version_status` (`prompt_id`, `version_status`),
    KEY `idx_prompt_version_hash` (`prompt_id`, `content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='Prompt不可变版本';

CREATE TABLE `t_prompt_release_log` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `prompt_id` bigint NOT NULL COMMENT 'Prompt定义ID',
    `operation_type` varchar(32) NOT NULL COMMENT 'PUBLISH/OFFLINE/ROLLBACK',
    `target_version_id` bigint DEFAULT NULL COMMENT '目标版本ID',
    `previous_version_id` bigint DEFAULT NULL COMMENT '原生效版本ID',
    `operation_reason` varchar(1000) NOT NULL COMMENT '操作原因',
    `operator_id` varchar(64) NOT NULL COMMENT '操作人',
    `operation_result` varchar(32) NOT NULL COMMENT 'SUCCESS/FAILED',
    `failure_reason` varchar(1000) DEFAULT NULL COMMENT '失败原因',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_prompt_release_log` (`prompt_id`, `create_time`),
    KEY `idx_prompt_release_target` (`target_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='Prompt发布与回滚审计';

CREATE TABLE IF NOT EXISTS agentscope_sessions (
                                                   session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    state_key  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
    ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `t_conversation` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id` bigint NOT NULL COMMENT '归属用户',
    `title` varchar(200) NOT NULL COMMENT '会话标题',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/CLOSED',
    `runtime_session_id` varchar(255) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端生成的AgentScope会话映射',
    `message_count` int NOT NULL DEFAULT 0 COMMENT '已持久化消息数',
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

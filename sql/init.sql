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

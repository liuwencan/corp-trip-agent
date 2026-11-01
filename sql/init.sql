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

CREATE TABLE `user_provider_api_key` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `provider_code` varchar(32) NOT NULL COMMENT '第三方服务商编码，如TUNIU',
    `api_key_ciphertext` text NOT NULL COMMENT 'API Key加密密文',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/REVOKED',
    `remark` varchar(500) DEFAULT NULL COMMENT '备注',
    `created_by` bigint DEFAULT NULL COMMENT '创建人用户ID',
    `updated_by` bigint DEFAULT NULL COMMENT '最后修改人用户ID',
    `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_provider` (`user_id`, `provider_code`),
    KEY `idx_user_status` (`user_id`, `status`),
    CONSTRAINT `fk_user_provider_api_key_user`
        FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='用户第三方旅行平台API Key';

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

CREATE TABLE `t_intent_utterance` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `intent_code` varchar(64) NOT NULL COMMENT '目标意图编码，对应IntentType.targetIntent',
    `utterance_text` varchar(500) NOT NULL COMMENT '用户口语化表达或子句',
    `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    `remark` varchar(500) DEFAULT NULL COMMENT '备注',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_intent_utterance` (`intent_code`, `utterance_text`),
    KEY `idx_intent_utterance_code_status` (`intent_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='意图口语化表达维护';

CREATE TABLE `trip_order` (
    `id` varchar(64) NOT NULL COMMENT '出差申请单ID',
    `user_id` varchar(64) NOT NULL COMMENT '申请人ID',
    `destination` varchar(256) NOT NULL COMMENT '目的地',
    `departure_city` varchar(128) NOT NULL COMMENT '出发城市',
    `departure_date` varchar(32) NOT NULL COMMENT '出发日期',
    `return_date` varchar(32) NOT NULL COMMENT '返回日期',
    `purpose` varchar(512) NOT NULL COMMENT '出差事由',
    `status` varchar(32) NOT NULL COMMENT '申请单状态',
    `approval_id` varchar(64) DEFAULT NULL COMMENT '关联审批记录ID',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_trip_order_user_time` (`user_id`, `updated_at`),
    KEY `idx_trip_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='出差申请单';

CREATE TABLE `trip_approval_record` (
    `id` varchar(64) NOT NULL COMMENT '审批记录ID',
    `user_id` varchar(64) NOT NULL COMMENT '申请人ID',
    `title` varchar(256) NOT NULL COMMENT '审批标题',
    `status` varchar(32) NOT NULL COMMENT '审批状态',
    `approval_form` json NOT NULL COMMENT '审批表单快照',
    `remark` varchar(512) DEFAULT NULL COMMENT '审批备注',
    `submit_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `order_id` varchar(64) NOT NULL COMMENT '关联出差申请单ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_approval_order` (`order_id`),
    KEY `idx_trip_approval_status_time` (`status`, `submit_time`),
    KEY `idx_trip_approval_user_time` (`user_id`, `submit_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC COMMENT='出差申请审批记录';

INSERT INTO `t_intent_utterance` (`intent_code`, `utterance_text`, `status`, `remark`) VALUES
('TRAVEL_POLICY_QUERY', '公司出差住酒店能报销多少钱', 'ACTIVE', '住宿标准咨询'),
('TRAVEL_POLICY_QUERY', '出差坐飞机有什么级别要求', 'ACTIVE', '交通标准咨询'),
('TRAVEL_POLICY_QUERY', '出差每天的餐补是多少', 'ACTIVE', '餐饮补贴咨询'),
('TRAVEL_POLICY_QUERY', '去外地出差住宿有没有上限', 'ACTIVE', '住宿限额咨询'),
('TRAVEL_POLICY_QUERY', '公司差旅报销需要遵守什么规定', 'ACTIVE', '差旅制度咨询'),
('TRAVEL_POLICY_QUERY', '高铁出差可以报销一等座吗', 'ACTIVE', '交通席别标准咨询'),
('TRAVEL_INFO_QUERY', '帮我看看杭州明天会不会下雨', 'ACTIVE', '天气信息查询'),
('TRAVEL_INFO_QUERY', '深圳机场到市区怎么走方便', 'ACTIVE', '当地交通查询'),
('TRAVEL_INFO_QUERY', '北京出差住哪一片交通比较方便', 'ACTIVE', '目的地出行信息'),
('TRAVEL_INFO_QUERY', '附近有什么适合商务宴请的餐厅', 'ACTIVE', '目的地餐饮查询'),
('TRAVEL_INFO_QUERY', '成都有什么值得顺路去的地方', 'ACTIVE', '目的地景点查询'),
('TRAVEL_INFO_QUERY', '帮我查一下上海到虹桥站的路线', 'ACTIVE', '路线信息查询'),
('CREATE_TRIP_APPLICATION', '帮我申请下周去深圳出差', 'ACTIVE', '创建出差申请'),
('CREATE_TRIP_APPLICATION', '我要提交一个北京出差申请', 'ACTIVE', '提交出差申请'),
('CREATE_TRIP_APPLICATION', '帮我发起去杭州的差旅申请', 'ACTIVE', '发起差旅申请'),
('CREATE_TRIP_APPLICATION', '下个月去广州出差，帮我走申请流程', 'ACTIVE', '出差申请流程'),
('CREATE_TRIP_APPLICATION', '给我新建一份去成都的出差申请', 'ACTIVE', '新建出差申请'),
('CREATE_TRIP_APPLICATION', '我需要申请去上海参加客户会议', 'ACTIVE', '会议出差申请'),
('PLAN_ITINERARY', '帮我安排一下这次北京出差的行程', 'ACTIVE', '出差行程规划'),
('PLAN_ITINERARY', '规划一个周一到周三的上海行程', 'ACTIVE', '多日行程规划'),
('PLAN_ITINERARY', '帮我设计从机场到客户公司的路线', 'ACTIVE', '出行路线规划'),
('PLAN_ITINERARY', '去广州见客户，交通和住宿怎么安排', 'ACTIVE', '交通住宿规划'),
('PLAN_ITINERARY', '给我排一下杭州出差每天的日程', 'ACTIVE', '日程安排'),
('PLAN_ITINERARY', '帮我做一份深圳两天的拜访路线', 'ACTIVE', '客户拜访路线规划'),
('BOOK_TRAVEL', '帮我订一张明天去北京的机票', 'ACTIVE', '机票预订'),
('BOOK_TRAVEL', '预订今天下午去上海的高铁票', 'ACTIVE', '火车票预订'),
('BOOK_TRAVEL', '帮我在客户附近订个酒店', 'ACTIVE', '酒店预订'),
('BOOK_TRAVEL', '给我买一张周五回杭州的车票', 'ACTIVE', '返程车票预订'),
('BOOK_TRAVEL', '安排一下下周去广州的航班', 'ACTIVE', '航班预订'),
('BOOK_TRAVEL', '帮我订两晚深圳的住宿', 'ACTIVE', '住宿预订'),
('MANAGE_ORDER', '帮我查一下之前订的机票信息', 'ACTIVE', '查询已有订单'),
('MANAGE_ORDER', '我想把明天的航班改签到晚上', 'ACTIVE', '机票改签'),
('MANAGE_ORDER', '帮我退掉这张火车票', 'ACTIVE', '火车票退票'),
('MANAGE_ORDER', '取消我在杭州订的酒店', 'ACTIVE', '酒店取消'),
('MANAGE_ORDER', '查看一下我的出差行程单', 'ACTIVE', '行程单查询'),
('MANAGE_ORDER', '这个酒店订单现在是什么状态', 'ACTIVE', '订单状态查询'),
('APPROVAL_QUERY', '我的出差申请审批到哪一步了', 'ACTIVE', '审批进度查询'),
('APPROVAL_QUERY', '帮我看看这次差旅申请有没有批下来', 'ACTIVE', '审批结果查询'),
('APPROVAL_QUERY', '谁还没有审批我的出差申请', 'ACTIVE', '审批人进度查询'),
('APPROVAL_QUERY', '这个申请现在是通过还是被驳回了', 'ACTIVE', '申请结果查询'),
('APPROVAL_QUERY', '查一下我上周提交的出差申请状态', 'ACTIVE', '申请状态查询'),
('APPROVAL_QUERY', '帮我处理待审批的出差申请', 'ACTIVE', '待办审批处理'),
('REIMBURSEMENT', '帮我整理一下这次出差的报销材料', 'ACTIVE', '报销材料整理'),
('REIMBURSEMENT', '这趟出差的费用要怎么报销', 'ACTIVE', '差旅费用报销'),
('REIMBURSEMENT', '查看一下我的报销申请到哪了', 'ACTIVE', '报销进度查询'),
('REIMBURSEMENT', '帮我汇总机票酒店和餐费发票', 'ACTIVE', '报销票据汇总'),
('REIMBURSEMENT', '出差回来后需要提交哪些报销凭证', 'ACTIVE', '报销凭证咨询'),
('REIMBURSEMENT', '帮我生成这次出差的费用清单', 'ACTIVE', '差旅费用清单'),
('UNKNOWN', '帮我写一首关于春天的诗', 'ACTIVE', '非差旅请求负向样例'),
('UNKNOWN', '给我推荐几部最近的电影', 'ACTIVE', '非差旅请求负向样例'),
('UNKNOWN', '帮我写一段 Python 数据处理代码', 'ACTIVE', '非差旅请求负向样例'),
('UNKNOWN', '怎么做一道红烧肉', 'ACTIVE', '非差旅请求负向样例'),
('UNKNOWN', '解释一下什么是光合作用', 'ACTIVE', '非差旅请求负向样例'),
('UNKNOWN', '帮我设计一个健身计划', 'ACTIVE', '非差旅请求负向样例');

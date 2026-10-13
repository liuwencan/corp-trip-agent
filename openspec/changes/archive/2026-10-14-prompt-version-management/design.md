# Design

## Context

项目是单模块 Spring Boot 应用，已有 `system.config` 模块、MyBatis-Plus、统一 `Response`/`PageResponse` 和 `t_config` 动态配置表。当前代码中没有 Prompt 领域模型、版本历史或发布审计能力。

本设计只覆盖 Prompt 版本管理。Prompt 正文不放入 `t_config.config_value`，现有 `t_config` 表结构保持不变。评测数据集、效果指标和自动优化属于后续独立变更。

## Goals / Non-Goals

**Goals:**

- 让每次 Prompt 修改形成不可变版本，并记录递增版本号、修改人、修改时间和修改原因。
- 让版本历史可查询，当前生效版本明确且同一 Prompt 同时最多只有一个生效版本。
- 支持草稿、发布、下线和回滚，并完整记录操作审计。
- 为未来接入评测或 GEPA 保留清晰的 Prompt 版本接口，但不在本期实现这些能力。

**Non-Goals:**

- 不实现评测数据集、评测运行、效果指标、版本效果对比或发布指标门槛。
- 不实现 GEPA 自动生成、自动迭代或自动发布 Prompt。
- 不建设通用用户、角色和组织权限系统；只抽象操作人来源，接入现有认证后复用统一身份。
- 不修改现有业务 Agent 的运行逻辑和 `t_config` 表结构。

## Decisions

### 1. Prompt 定义与 Prompt 版本分表

使用 `t_prompt` 保存稳定的业务标识、用途和当前生效版本，使用 `t_prompt_version` 保存不可变的具体内容。一个 Prompt 定义对应一条线性版本序列。

相比在 `t_config.config_value` 中覆盖整段 JSON，这种分表方式可以保存长文本、查询历史、建立唯一版本约束并支持发布回滚，同时不破坏现有动态配置约定。

### 2. 版本号由调用方提交，服务层校验严格递增

创建版本时请求携带 `versionNo`，服务层在事务中读取同一 Prompt 的当前最大版本号，只有 `versionNo > max(versionNo)` 才允许创建。推荐调用方按 `max + 1` 提交；系统不允许跳回旧版本号，也不允许覆盖已有版本。

数据库使用唯一索引 `uk_prompt_version_no(prompt_id, version_no)` 防止重复。并发创建时，服务层锁定 Prompt 定义行后读取最大版本号并插入；唯一键冲突时事务失败并返回明确错误，调用方重新读取最新版本后再提交。

不引入 `parent_version_id`。本期版本历史是线性的，版本号和创建时间已经足够表达先后关系。未来如果引入 GEPA 候选树、分支或合并，再通过独立变更增加候选关系模型。

### 3. 版本正文只追加，不原地更新

系统只提供创建新版本的写入路径，不提供更新已创建版本正文、版本号、创建人和修改原因的接口。发布、下线和回滚只改变状态、当前引用和操作日志，不改变历史正文。

版本状态使用字符串值，第一期包括：`DRAFT`、`PUBLISHED`、`DEPRECATED`、`ROLLED_BACK`。状态转换规则如下：

| 当前状态 | 允许操作 | 结果 |
| --- | --- | --- |
| `DRAFT` | 发布 | `PUBLISHED` |
| `PUBLISHED` | 发布其他版本 | `DEPRECATED` |
| `PUBLISHED` | 下线 | `DEPRECATED`，当前引用清空 |
| `DEPRECATED` | 回滚 | `PUBLISHED` |
| `ROLLED_BACK` | 作为历史版本再次发布 | `PUBLISHED` |

已发布版本不能编辑；历史版本需要通过创建新版本或执行回滚改变当前生效引用。

### 4. 发布、下线和回滚使用同一事务边界

操作时锁定 `t_prompt` 当前行，在同一事务中完成资源校验、版本状态更新、当前版本引用更新和审计日志写入：

1. 校验目标版本属于当前 Prompt 定义；
2. 校验目标状态允许执行本次操作；
3. 将旧当前版本转为 `DEPRECATED` 或 `ROLLED_BACK`；
4. 将目标版本转为 `PUBLISHED`，或在下线时清空当前引用；
5. 更新 `t_prompt.current_version_id`；
6. 写入 `t_prompt_release_log`。

这样可以保证同一个 Prompt 最多只有一个当前生效版本。数据库不依赖物理外键实现关联约束，因为现有项目没有统一外键策略；关联检查由 Service 完成，事务和索引保证一致性。

### 5. API 和项目分层

新增 `cn.lwccxy.trip.system.prompt` 模块，按现有约定拆分 `controller`、`service`、`mapper`、`domain.entity`、`domain.form`、`domain.vo` 和 `enums`。Controller 只接收 Form 并返回 `Response`/`PageResponse`，Entity 不直接出现在接口响应中。

建议接口边界如下：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/system/prompt` | 创建 Prompt 定义 |
| POST | `/system/prompt/query` | 分页查询 Prompt 定义 |
| GET | `/system/prompt/{id}` | 查询定义和当前生效版本 |
| POST | `/system/prompt/{id}/versions` | 创建指定递增版本 |
| GET | `/system/prompt/{id}/versions` | 查询版本历史 |
| GET | `/system/prompt/{id}/versions/{versionId}` | 查询版本详情 |
| POST | `/system/prompt/{id}/versions/{versionId}/publish` | 发布版本 |
| POST | `/system/prompt/{id}/offline` | 下线当前版本 |
| POST | `/system/prompt/{id}/rollback` | 回滚到历史版本 |

项目当前未发现统一认证上下文。实现时引入 `OperatorIdentityProvider` 接口，Service 只依赖该接口取得操作人，不从请求正文中的可编辑字段推断身份；在认证系统接入前使用受控的内部调用适配器，并禁止匿名写操作。

### 6. Database schema

以下 SQL 是本次功能需要加入 `sql/init.sql` 的新表定义，也应作为已有环境的独立迁移脚本内容。表之间暂不声明物理外键，关联完整性由 Service 在事务中检查。

```sql
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
    `system_prompt` longtext NOT NULL COMMENT '系统Prompt正文',
    `user_template` longtext DEFAULT NULL COMMENT '用户消息模板',
    `output_schema` longtext DEFAULT NULL COMMENT '输出结构约束JSON文本',
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
```

### 7. Error handling and security

- Prompt key、名称、能力标识、版本号和修改原因由 Form 校验；重复 key、非递增版本号、非法状态、不存在资源和无效回滚目标返回稳定错误码。
- Prompt 正文可以包含敏感业务规则，详情接口必须进行权限校验；日志不得输出完整 Prompt、模型密钥或其他敏感信息。
- 版本内容使用 SHA-256 摘要用于一致性校验，但摘要不能替代权限控制。
- 发布、下线和回滚必须记录操作人和原因；失败操作也记录失败原因，便于追溯。

## Risks / Trade-offs

- [风险] 并发创建版本可能产生相同的下一个版本号 → [缓解] 锁定 Prompt 定义行后读取最大版本号，并使用 `(prompt_id, version_no)` 唯一索引兜底；冲突返回可重试错误。
- [风险] 手工发布与并发回滚产生两个当前版本 → [缓解] 操作事务锁定 Prompt 定义行，在事务内更新当前版本引用和审计日志。
- [风险] 没有物理外键导致孤儿引用 → [缓解] Service 层统一校验 Prompt、版本和日志关联，并用数据库集成测试覆盖删除/不存在场景；本期不提供物理删除版本接口。
- [取舍] 不引入 `parent_version_id`，结构更简单且符合当前线性版本需求；未来需要分支或候选树时新增独立关系模型，避免提前承担复杂度。
- [取舍] Prompt 正文直接保存在数据库，查询和回滚简单，但大规模长文本会增加存储压力；当前 Prompt 规模适合使用 `LONGTEXT`，后续可迁移到对象存储并保留摘要。

## Migration Plan

1. 核对目标环境 MySQL 版本、字符集和现有表结构，备份数据库；不得在应用启动时自动执行 DDL。
2. 将三张新表加入 `sql/init.sql`，保证新环境初始化完整。
3. 为已有环境新增独立迁移脚本，例如 `sql/migration/V20261010__prompt_version_management.sql`，按 `t_prompt`、`t_prompt_version`、`t_prompt_release_log` 顺序执行。
4. 部署 Entity、Mapper、Service 和 Controller 后，先验证创建定义、创建递增版本、历史查询，再开放发布、下线和回滚接口。
5. 应用版本回滚时保留新表和审计数据，关闭新接口即可。只有在确认无数据且完成备份后，才允许按发布日志、版本、定义的逆序删除新表；生产环境默认不执行删表回滚。

## Open Questions

无。操作人身份的具体适配器可在实现阶段接入项目最终确定的鉴权组件，不改变本设计的数据结构和接口行为。

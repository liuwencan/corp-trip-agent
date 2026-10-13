# Proposal

## Why

CorpTripAgent 后续需要持续调整 Agent、意图识别、槽位抽取、政策判断和工具编排 Prompt，但目前缺少统一的版本管理机制。Prompt 一旦直接覆盖，无法可靠回答“谁在什么时候因为什么修改了什么内容、当前线上使用的是哪一版”，也无法安全地进行发布、下线和回滚。

现在建立 Prompt 版本管理能力，可以把 Prompt 作为可追踪的软件资产管理：每次修改形成不可变版本，版本号严格递增，记录修改人和原因，经过管理操作后发布，并支持查看历史、下线和回滚。

## What Changes

- 新增 Prompt 定义管理，支持按业务场景、Agent 能力或用途维护 Prompt，并标识当前生效版本。
- 新增 Prompt 版本迭代，版本内容、模板、输出约束、创建人和修改原因均可追溯；已创建版本不可直接覆盖。
- 新增严格递增的版本号规则，同一个 Prompt 的新版本号必须大于当前最大版本号，并由唯一约束防止重复版本。
- 新增版本状态流转，支持草稿、已发布、已废弃和已回滚等状态，并限制非法状态转换。
- 新增发布、下线和回滚记录，记录操作人、操作原因、目标版本、原生效版本和操作时间。
- 新增管理接口，覆盖 Prompt 列表/详情、创建版本、查看版本历史、发布、下线和回滚。
- 新增数据库初始化结构和独立迁移 SQL，覆盖 Prompt 定义、Prompt 版本和发布审计三类数据；不修改现有 `t_config` 表结构。

## Capabilities

### New Capabilities

- `prompt-version-management`: 管理 Prompt 定义、严格递增的不可变版本、版本状态、修改审计、发布/下线/回滚以及当前生效版本。

### Modified Capabilities

无。

## Impact

- 新增 `system.prompt` 领域模块，包含 Entity、Mapper、Service、Form、VO、Controller 和状态枚举，复用项目现有 `Response`、`PageResponse`、Validation 和 MyBatis-Plus 约定。
- 新增数据库表：Prompt 定义、Prompt 版本和发布记录；具体字段、索引、唯一约束、外键取舍和迁移顺序在 `design.md` 中定义。
- 更新 `sql/init.sql`，并新增面向已存在环境的独立迁移脚本；实施时必须先备份并核对实际数据库结构。
- 新增管理接口和服务层逻辑，但不改变现有业务接口响应格式，不直接向接口层暴露 Entity。
- 需要补充服务层、递增版本号、状态流转、发布、下线和回滚行为测试，并按数据库变更要求执行完整 Maven 验证。

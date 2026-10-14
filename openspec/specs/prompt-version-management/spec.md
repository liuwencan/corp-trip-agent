# prompt-version-management Specification

## Purpose

为 CorpTripAgent 提供可审计的 Prompt 生命周期管理能力，使 Prompt 修改、验证、发布和回滚都具备明确的版本边界与责任记录。

## Requirements

### Requirement: Prompt definition can be registered

系统 SHALL 允许授权管理人员创建和维护 Prompt 定义。每个定义至少包含唯一业务标识、名称、用途/场景、所属 Agent 能力和当前生效版本引用。

#### Scenario: Create a prompt definition

- **WHEN** 授权管理人员提交未占用的业务标识、名称和用途
- **THEN** 系统创建 Prompt 定义，并将其置为可创建版本的初始状态

#### Scenario: Reject duplicate business key

- **WHEN** 管理人员提交已存在的 Prompt 业务标识
- **THEN** 系统拒绝请求并返回明确的重复标识错误，不能创建第二个定义

### Requirement: Prompt versions are immutable and strictly increasing

系统 SHALL 为每次一次性 Prompt 内容变更创建独立版本，并保存完整 Prompt 正文、版本号、创建人、创建时间、修改原因和内容摘要。同一个 Prompt 的新版本号 MUST 大于已有最大版本号，已创建版本的内容和修改审计信息 MUST NOT 被直接覆盖。

#### Scenario: Create a new version

- **WHEN** 管理人员提交完整的一次性 Prompt 正文，并填写大于当前最大版本号的版本号
- **THEN** 系统创建新的版本记录，并保留新旧版本的独立内容

#### Scenario: Reject a non-increasing version

- **WHEN** 管理人员提交的版本号小于或等于同一 Prompt 的当前最大版本号
- **THEN** 系统拒绝创建版本，并返回版本号必须递增的错误

#### Scenario: Reject version without change reason

- **WHEN** 管理人员提交 Prompt 版本但未填写修改原因
- **THEN** 系统拒绝创建版本，并提示修改原因是必填项

#### Scenario: Preserve historical content

- **WHEN** 新版本创建或后续版本发布
- **THEN** 已有版本的内容、创建人、创建时间和修改原因保持不变，并可按版本号查询

### Requirement: Version lifecycle is constrained

系统 SHALL 支持草稿、已发布、已废弃和已回滚状态，并 SHALL 只允许符合生命周期规则的状态转换。无效转换 MUST 被拒绝且不得改变版本状态。

#### Scenario: Publish a complete draft

- **WHEN** 草稿版本的必填内容完整且管理人员执行发布
- **THEN** 版本状态从草稿变为已发布，并成为当前生效版本

#### Scenario: Reject invalid transition

- **WHEN** 管理人员尝试修改已创建版本内容，或执行不符合生命周期规则的状态转换
- **THEN** 系统拒绝操作并保留原状态和内容

### Requirement: Exactly one version is effective per prompt

系统 SHALL 保证同一个 Prompt 定义在任一时刻最多只有一个当前生效版本。发布新版本时，系统 MUST 原子地替换当前生效版本引用，并将原生效版本转入可追溯的历史状态。

#### Scenario: Publish a complete version

- **WHEN** 草稿版本内容完整且管理人员执行发布
- **THEN** 系统将该版本标记为已发布，更新 Prompt 定义的当前生效版本，并确保同一 Prompt 没有其他生效版本

#### Scenario: Reject an unavailable version

- **WHEN** 管理人员尝试发布不存在、已回滚或不属于当前 Prompt 的版本
- **THEN** 系统拒绝发布并返回版本状态错误，不改变当前生效版本

### Requirement: Publish and rollback operations are auditable

系统 SHALL 记录发布、下线和回滚操作的操作人、操作原因、目标版本、原生效版本、操作时间和操作结果。回滚 MUST 只能选择已有且可发布的历史版本，并生成新的操作记录。

#### Scenario: Roll back to a previous version

- **WHEN** 管理人员选择一个属于当前 Prompt 且状态允许发布的历史版本执行回滚并填写原因
- **THEN** 系统将该历史版本恢复为当前生效版本，记录原版本和目标版本，并保留之前发布链路

#### Scenario: Reject rollback to unavailable version

- **WHEN** 管理人员选择不存在、已删除或状态不允许发布的版本回滚
- **THEN** 系统拒绝回滚，不改变当前生效版本，并返回失败原因

### Requirement: Version history is queryable

系统 SHALL 提供按 Prompt 定义查询版本列表、状态、修改人、修改原因、发布时间和发布操作的能力，并支持查看任意两个版本的完整 Prompt 正文差异。

#### Scenario: Inspect version history

- **WHEN** 管理人员查询某个 Prompt 定义的版本历史
- **THEN** 系统按版本号或创建时间返回版本及其审计信息，且不返回被覆盖的伪版本

#### Scenario: Compare two version contents

- **WHEN** 管理人员选择同一 Prompt 定义下的两个版本进行比较
- **THEN** 系统返回完整 Prompt 正文差异

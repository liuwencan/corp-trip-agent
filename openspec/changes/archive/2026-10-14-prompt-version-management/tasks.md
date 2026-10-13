# Tasks

## 1. Database and Domain Foundation

- [x] 1.1 Add `t_prompt`, `t_prompt_version`, and `t_prompt_release_log` to `sql/init.sql`, add `sql/migration/V20261010__prompt_version_management.sql` for existing environments, and verify the scripts do not change `t_config` and execute in the documented order.
- [x] 1.2 Add Entity classes, status enums, Mapper interfaces, and indexes/unique-key assumptions under `cn.lwccxy.trip.system.prompt`; verify the source fields match the SQL columns and `mvn test` compiles the main sources.
- [x] 1.3 Add Form and VO models with Jakarta Validation for Prompt definitions, versions, publish, offline, rollback, and query operations; verify blank keys, non-positive version numbers, and missing reasons are rejected before service execution.

## 2. Prompt Version Lifecycle

- [x] 2.1 Implement Prompt definition creation, uniqueness checks, current-version lookup, and paginated query using `Response` and `PageResponse`; verify duplicate business keys return a stable error code and no duplicate row.
- [x] 2.2 Implement version creation with caller-supplied `versionNo`, content hashing, immutable Prompt content, mandatory change reason, and operator identity; verify the version is accepted only when `versionNo` is greater than the current maximum and existing versions cannot be overwritten.
- [x] 2.3 Implement concurrent version creation with a transaction that locks the Prompt definition row and a unique `(prompt_id, version_no)` constraint; verify competing requests cannot create duplicate version numbers and receive a retryable conflict error.
- [x] 2.4 Implement the version state machine for draft, published, deprecated, and rolled-back states; verify invalid transitions and edits to existing versions leave the original state and content unchanged.
- [x] 2.5 Implement transactional publish, offline, and rollback services with current-version replacement and release-log writes; verify at most one effective version exists and failed operations leave the current version unchanged.
- [x] 2.6 Add the `OperatorIdentityProvider` abstraction and its initial controlled adapter; verify write operations do not accept an arbitrary operator identity from untrusted Prompt fields and audit records always contain an operator.
- [x] 2.7 Add service tests for strict version increase, immutability, state transitions, publish, offline, rollback, concurrency conflict, and audit-log persistence; verify `mvn test` passes for these branches.

## 3. Management APIs and Security

- [x] 3.1 Add the Prompt management controller endpoints described in `design.md` for definitions, versions, history, publish, offline, and rollback; verify all success and failure responses use `Response` or `PageResponse` and no Entity is serialized directly.
- [x] 3.2 Add version-history and detail VOs that expose version number, modification reason, operator, status, current-version marker, release history, and content differences; verify pagination and missing-resource errors match existing project conventions.
- [x] 3.3 Add permission checks or the project’s current authorization integration at the controller/service boundary, and prevent full Prompt text or credentials from being written to logs; verify writes without an authenticated operator are rejected.
- [x] 3.4 Add structured logs for Prompt ID, version ID, operation type, operator, status, and failure reason without logging full Prompt text; verify a publish, offline, or rollback can be traced to its release log.

## 4. Integration Verification and Delivery

- [x] 4.1 Validate database integration for table initialization, unique Prompt keys, strict version uniqueness, version history queries, current-version replacement, and release-log persistence; the initialization and migration scripts both ran successfully on temporary clean MySQL databases.
- [x] 4.2 Exercise the service flow: create versions 1 and 2, reject version 1 again, publish version 2, query history, roll back to version 1, and verify the in-memory service assertions and release logs pass.
- [x] 4.3 Run `mvn clean test` and `mvn clean package`, inspect `git diff`/`git status`, and verify no `target/`, local configuration, password, Token, or private key is included in the change; Maven verification passed, while pre-existing local configuration changes remain untouched.

## Workflow follow-up

- Review the revised proposal, spec, design, and task checklist before implementation.
- Start implementation with `$openspec-apply-change` or `/openspec-apply-change`.
- Archive the change only after implementation and verification are complete.

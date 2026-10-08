# CorpTripAgent 开发规范

本文档是 CorpTripAgent 项目的开发、验证和交付规则。后续修改代码时，应先阅读本文档，并遵循项目现有的分层、响应格式、数据库结构和构建流程。

## 项目概况

CorpTripAgent 是一个企业内部智能出差助手后端项目，当前为单模块 Maven 项目。

主要技术栈：

- Java 21
- Spring Boot 3.5.6
- Spring Web
- Spring Validation
- MyBatis-Plus 3.5.9
- MySQL，MySQL Connector/J
- Druid 1.2.23 数据库连接池
- Fastjson2 2.0.43
- Lombok 1.18.30
- AgentScope Harness 2.0.3
- Maven

应用入口：`cn.lwccxy.trip.CorpTripApplication`

默认配置文件：`src/main/resources/config/application.yml`

默认服务端口：`8080`

默认数据库：`corptrip`

## 目录约定

- `src/main/java/cn/lwccxy/trip/`：Java 源码
- `src/main/java/cn/lwccxy/trip/controller/`：业务接口控制器
- `src/main/java/cn/lwccxy/trip/system/`：系统管理功能，例如动态配置
- `src/main/java/cn/lwccxy/trip/common/`：公共响应、异常、配置和工具类
- `src/main/resources/config/`：Spring Boot 配置文件
- `sql/`：数据库初始化脚本和必要的 SQL
- `src/test/java/`：测试代码
- `target/`：Maven 构建产物，不参与源码修改

新增功能应放入对应业务或系统模块，避免把业务逻辑堆积到控制器中。控制器负责参数接收和响应返回，服务层负责业务逻辑，Mapper 负责数据库访问，Entity 只映射数据库表结构。

## 编码规则

1. 优先复用项目已有的 Spring、MyBatis-Plus、公共响应和异常处理方式，不引入重复的基础设施。
2. 接口返回优先使用 `cn.lwccxy.trip.common.response.Response` 或 `PageResponse`，保持成功标识、消息、错误码和数据结构一致。
3. 请求参数使用独立的 `domain.form` 类，并使用 Jakarta Validation 注解校验必填字段、长度和数值范围。
4. 返回数据使用 `domain.vo` 类，不直接把数据库 Entity 暴露给接口层。
5. 数据库字段使用下划线命名，Java 属性使用驼峰命名，依赖 MyBatis-Plus 的下划线转驼峰配置。
6. 修改数据后必须同步更新本地缓存、索引或其他运行时状态。删除、改名等操作要清理旧 key。
7. 业务读取动态配置时使用配置服务提供的读取方法，并提供合理默认值。配置值在数据库中按字符串保存，由业务读取方决定布尔值、整数、长整数、小数或 JSON 的解析方式。
8. 动态配置表 `t_config` 必须保持现有结构，不增加 `value_type`、类型字段或额外约束。完整结构以 `sql/init.sql` 和实际数据库表为准。
9. 不在应用启动逻辑中擅自执行 `ALTER TABLE`、删除数据或其他破坏性数据库操作。数据库结构变更必须单独提供 SQL，并说明执行顺序、影响范围和回滚方式。
10. 不提交密码、Token、私钥和本地环境配置。`application-local.yml`、`.env` 等本地配置只在本机使用。
11. 修改范围应保持聚焦，不要顺手重构无关模块或覆盖已有未提交改动。
12. 注释只解释不明显的业务规则、兼容处理或复杂算法；简单代码不添加重复性注释。

## 动态配置约定

动态配置使用 `t_config` 表中的以下字段：

- `config_key`：配置 key
- `config_value`：配置值，统一以字符串保存
- `config_name`：配置名称
- `remark`：备注

后台修改配置后，应立即更新服务内缓存，使后续业务读取使用新值。多实例部署时，单机缓存不会自动同步；涉及多实例实时一致性时，需要明确引入 Redis 发布订阅或统一配置中心，不能假设本地缓存会自动同步。

## 功能开发流程

完成一个功能时按以下顺序执行：

1. 阅读相关模块、公共响应类、异常类、Entity、Mapper、Service 和 Controller，确认已有约定。
2. 明确接口请求、响应、错误码、数据库影响和缓存影响。
3. 先修改或新增领域对象，再实现服务层逻辑，最后接入控制器。
4. 涉及数据库时同步检查 `sql/init.sql`，必要时新增独立迁移脚本。SQL 必须与现有实际表结构一致。
5. 为有分支、边界条件或数据转换的服务逻辑补充测试；接口行为需要时补充 Spring Web 层测试。
6. 完成编译、自动化测试和启动验证。
7. 检查 `git diff`、`git status` 和敏感信息，确认没有误改配置、日志、构建产物或无关文件。

## 编译和测试

PowerShell 环境下，在项目根目录执行：

```powershell
# 编译主代码
mvn -DskipTests compile

# 执行全部测试
mvn test

# 清理并打包
mvn clean package

# 跳过测试打包，仅用于确认构建产物能够生成
mvn clean package -DskipTests
```

提交代码前至少执行：

```powershell
mvn test
mvn -DskipTests compile
```

如果修改了依赖、Spring 配置、Mapper、Entity、数据库字段或启动流程，应执行：

```powershell
mvn clean test
mvn clean package
```

测试通过的判断标准：Maven 返回 `BUILD SUCCESS`，没有编译错误、测试失败或应用上下文初始化失败。当前项目如果没有测试源码，`mvn test` 可能显示 `No tests to run`；这只能说明测试阶段执行成功，不能替代接口和数据库验证。

## 启动和接口验证

启动前确认：

1. Java 版本为 21：`java -version`
2. MySQL 已启动。
3. 数据库 `corptrip` 已创建。
4. `sql/init.sql` 已执行，表结构与代码一致。
5. `src/main/resources/config/application.yml` 中的数据库地址、用户名和密码适合当前本地环境。

启动应用：

```powershell
mvn spring-boot:run
```

或先打包后启动：

```powershell
mvn clean package -DskipTests
java -jar target/corp-trip-agent-1.0-SNAPSHOT.jar
```

启动验证应检查：

- 日志中没有 `BeanCreationException`、`SQLSyntaxErrorException`、`Failed to configure a DataSource` 或端口占用错误。
- 应用监听 `8080` 端口。
- 涉及数据库的功能能够正常查询、插入、更新和删除。
- 动态配置修改后，业务读取能够立即得到新值。
- 分页接口检查页码、总数、页大小和总页数是否正确。
- 异常场景检查 HTTP 响应结构是否仍符合 `Response` 或 `PageResponse` 约定。

动态配置接口变更时，至少验证以下场景：

1. 查询配置列表和按 key 查询。
2. 新增配置，确认数据库记录和缓存都有数据。
3. 更新配置名称、key、value 和备注，确认旧 key 不再命中。
4. 只更新配置值，确认业务读取立即生效。
5. 删除配置，确认数据库记录和缓存都被清理。
6. 查询不存在的 key、重复 key 和非法请求参数，确认错误码和错误消息合理。

## 数据库变更流程

数据库变更必须遵循以下流程：

1. 先确认现有数据库结构，不根据 Entity 猜测生产表结构。
2. 更新 `sql/init.sql`，保证新环境可以初始化。
3. 为已存在环境新增独立迁移 SQL，脚本要标明前置版本和执行顺序。
4. SQL 执行前备份受影响的数据或表，并记录执行结果。
5. 执行后重新启动应用并验证相关接口。
6. 检查 Entity、Mapper 查询列和数据库实际列完全一致。

当前 `t_config` 表不包含 `value_type` 字段。任何查询、插入、更新 SQL、Entity、DTO 或 VO 都不得引用该字段。

## 日志和问题定位

遇到启动或接口错误时，按以下顺序排查：

1. 查看最底层 `Caused by`，先处理真正的数据库、参数或 Bean 初始化错误。
2. 对照实际数据库表结构检查 Mapper 生成的 SQL。
3. 检查请求 JSON 与 Form 校验规则是否一致。
4. 检查数据库写入成功后缓存是否同步更新。
5. 需要排查 SQL 时，将 `application.yml` 中相关日志级别临时调整为 DEBUG，验证完成后恢复合理级别。

日志中不得输出密码、Token、完整身份证号、完整手机号或其他敏感信息。

## 提交前检查清单

- [ ] 代码放在正确的模块和分层中。
- [ ] 请求参数有必要的校验。
- [ ] 接口响应遵循 `Response` 或 `PageResponse`。
- [ ] Entity、Mapper 和实际数据库结构一致。
- [ ] 动态配置的数据库值和缓存状态一致。
- [ ] 已执行 `mvn test`。
- [ ] 已根据改动范围执行 `mvn -DskipTests compile` 或 `mvn clean package`。
- [ ] 已进行必要的启动和接口验证。
- [ ] 已检查 `git diff` 和 `git status`。
- [ ] 没有提交敏感信息、`target/`、IDE 文件或无关改动。

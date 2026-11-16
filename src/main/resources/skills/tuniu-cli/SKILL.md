---
name: tuniu-cli
description: 企业差旅助手，通过 tuniu CLI 查询和预订酒店住宿、国内/国际航班及火车票（含高铁、动车）。调用前按 references/ 中对应服务说明确认工具名和参数。
version: 1.1.2
minCliVersion: 1.1.4
metadata: {"openclaw": {"emoji": "🧳", "category": "travel", "tags": ["途牛", "企业差旅", "机票", "国际机票", "酒店", "火车票", "高铁", "动车", "预订"], "priority": 100, "requires": {"bins": ["node", "npm", "tuniu"], "node": ">=18.0.0"}, "env": {"TUNIU_API_KEY": {"type": "string", "description": "由项目 Hook 按当前用户注入的 API Key", "required": false}}}}
---

# 途牛旅行助手

> **首次调用任一服务前，必须先 Read `references/<server>.md` 确认工具名与参数，禁止凭记忆拼写工具名。** 下单/取消、参数不确定或工具报错时同样必须先读该 reference。若文件不存在，改用 `tuniu schema <server> -o json`，必要时再 `tuniu help <server> <tool>`。

仅处理企业差旅相关的酒店住宿、国内/国际航班和火车票（含高铁、动车），通过 **tuniu CLI** 调用对应服务。其他旅行产品需求不属于本 skill 的处理范围，不调用或发现相关服务。

## 意图分流决策表

### 酒店

酒店住宿需求使用 `hotel`（`references/hotel.md`），首选工具 `tuniuHotelSearch`。

### 机票国内 / 国际分流（调用前必须执行）

收到机票/航班类需求时，**先按出发地、目的地判断航线类型，再选 server**，不要直接默认 `flight`：

| 航线类型 | 判断依据（以城市为准） | server | 参考文件 | 首选工具 |
|----------|------------------------|--------|----------|----------|
| **国内** | 出发地与目的地均为中国大陆城市 | `flight` | `references/flight.md` | `searchLowestPriceFlight` |
| **国际** | 出发地或目的地任一为境外（含港澳台） | `intelflight` | `references/intelflight.md` | `list_intel_flights` |

**分流规则**：

1. **以出发地/目的地为准**；用户口头说「国内/国际/出国」仅作参考。冲突时按城市判断选 server。
2. 无法判断时先向用户确认航线类型，**确认前不要调用任一机票 MCP**。
3. 国内只用 `flight`，国际只用 `intelflight`；工具名与参数体系不同，不可混用。
4. 列表无 `intelflight` 时 → `tuniu discovery refresh && tuniu discovery list`。
5. **往返、多城按每一航段独立判断**；单次 `tuniu call` 只能选一个 server。

### 用户说什么 → 用什么服务

酒店和国内 / 国际机票按上文分流；火车票、高铁和动车使用下表：

| 用户意图 | server | 参考文件 | 首选工具 |
|----------|--------|----------|----------|
| 火车票/高铁/动车 | `train` | `references/train.md` | `searchLowestPriceTrain` |

## 环境自检

运行环境须 Node.js 18+ 与 tuniu-cli。首次调用前执行：

```bash
node --version    # 须 >= 18
npm --version
# 若 tuniu 未安装：npm install -g tuniu-cli@latest
tuniu --version   # 须 >= 头部 minCliVersion；不足则 tuniu update
```

## 认证与 API Key 安全

业务鉴权统一使用 `TUNIU_API_KEY`，由项目 Hook 根据当前用户注入，实现多用户鉴权。调用 skill 中的 CLI 命令时照常使用，不要尝试 OAuth 登录，也不要自行查询、设置、传递或拼接 API Key、Token 等凭证参数；Hook 会负责工具拦截与注入。

### 凭证安全约束

- 不读取、展示、记录或要求用户提供 API Key；不要把密钥写入命令、参数、日志或回复。
- 认证失败时停止当前调用并说明鉴权失败，交由项目 Hook / 服务配置检查；不要改用 OAuth、不要提示用户在 CLI 中配置 API Key，也不要反复重试。

## 基本命令

### 业务调用

```bash
tuniu call <server> <tool> -a '<JSON参数>'
tuniu list [server]                                # 列出服务/工具
tuniu help <server> [<tool>]                       # 查看参数说明（调用前不确定参数时必用）
tuniu schema [server]                              # 导出工具 Schema（可加 -o json）；references 缺失时必用
tuniu health [server]                              # 健康检查
tuniu update                     # 一键更新 CLI（并刷新本机已安装的 skill）
tuniu discovery refresh && tuniu discovery list   # 刷新服务列表
```

`--args` / `-a` 必须是合法 JSON 字符串；无参数用 `-a '{}'`。中文可直接写入。

### 服务工具链路（概览）

| 服务 | 搜索 → 详情 → 下单 |
|------|-------------------|
| `flight` | `searchLowestPriceFlight` → `multiCabinDetails` → `getBookingRequiredInfo` → `saveOrder` |
| `intelflight` | `list_intel_flights` → `get_intel_flight_details` → `getBookingRequiredInfo` → `create_intel_flight_order` |
| `hotel` | `tuniuHotelSearch` → `tuniuHotelDetail` → `tuniuHotelCreateOrder` |
| `train` | `searchLowestPriceTrain` → `queryTrainDetail` → `bookTrain` |

各服务调用模板、翻页规则、展示规范、场景示例见对应 `references/<server>.md`。

## 服务发现

以下情况须先 `tuniu discovery refresh && tuniu discovery list`：

- 首次使用保留的酒店、航班、火车服务，或对应工具不存在（退出码 102）
- 仅查找 `hotel`、`flight`、`intelflight`、`train` 这四类服务；不要据此扩展到其他旅行产品

## 响应与退出码

成功时 stdout 为 JSON：`{"success": true, "result": {...}}`。`getBookingRequiredInfo` 返回纯文本，勿强行 JSON 解析。

| 退出码 | 含义 | 处理建议 |
|--------|------|----------|
| 0 | 成功 | 解析 stdout JSON |
| 101 | 连接失败 | 重试或检查网络 |
| 102 | 工具不存在 | 优先读取 `error.details.available_tools` 改用真实工具名并重试；否则 `tuniu list <server> -o json`，再用 `tuniu help` 或 `tuniu schema <server> -o json` 确认。禁止继续用错误工具名重试 |
| 103 | 参数错误 | 运行 `tuniu help <server> <tool>` |
| 104 | 认证失败 | 停止调用并报告，由项目 Hook / 服务配置检查当前用户的 API Key 注入状态 |
| 105 | 超时 | 使用 `-t 60` 增加超时 |
| 106 | 服务器错误 | 联系服务提供方或稍后重试 |
| 107 | 配置错误 | 运行 `tuniu config show` 检查配置 |
| 108 | 未配置 API Key | 停止调用并报告，由项目 Hook / 服务配置检查当前用户的 API Key 注入状态 |
| 109 | API Key 无效 | 停止调用并报告，由项目 Hook / 服务配置检查当前用户的 API Key |
| 110 | 需要 OAuth 登录 | 本 skill 不执行 OAuth 登录；停止调用并报告鉴权失败 |
| 111 | OAuth 授权失败 | 本 skill 不处理 OAuth；停止调用并报告鉴权失败 |
| 112 | OAuth token 刷新失败 | 本 skill 不使用 OAuth；停止调用并报告鉴权失败 |
| 199 | 未知错误 | 使用 `-d` 调试模式 |

## 全局约束

1. **凭证与 PII 安全**：勿在回复或日志中暴露 `TUNIU_API_KEY` 或其他凭证；联系人姓名、手机号、乘客姓名、证件号仅在预订时发送至 MCP 服务。
2. **认证**：认证错误（退出码 104、108、109、110、111、112）停止调用并报告，由项目 Hook / 服务配置检查；不执行 OAuth 登录或自行设置 API Key。
3. **日期格式**：所有日期均为 `YYYY-MM-DD`。
4. **参数验证**：下单前必须先调用搜索/详情接口获取必需参数（如 `cabinPriceId`、`productId`、`resId`、`queryId`、`sourceId`、`preBookParam` 等）。
5. **翻页**：各服务翻页参数不同，注意区分（详见各 `references/<server>.md`）。
6. **下单结果**：下单成功后展示 `orderId`/`order_id` 与支付或详情链接，提醒用户完成支付，并在途牛 App/小程序跟进订单与出行通知。
7. **调试模式**：遇到问题时使用 `-d` 参数查看详细请求/响应。

## 必须阅读（强制）
调用途牛CLI的命令时, 有些命令会要求传入一个json格式的参数。

windows系统的统一传参json格式few-show如下(必须注意json参数的写法, 加上转义符号, 方便做解析):
set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxx" && tuniu call flight searchLowestPriceFlight -a "{\"departureCityName\":\"北京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\"}"


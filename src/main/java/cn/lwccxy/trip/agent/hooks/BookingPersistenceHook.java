package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.business.bookingrecord.domain.entity.BookingRecordEntity;
import cn.lwccxy.trip.business.bookingrecord.domain.enums.BookingType;
import cn.lwccxy.trip.business.bookingrecord.domain.vo.BookingRecordVO;
import cn.lwccxy.trip.business.bookingrecord.service.BookingRecordService;
import cn.lwccxy.trip.business.tripbooking.service.TripBookingRecordService;
import cn.lwccxy.trip.common.response.Response;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/** 监听途牛 Shell 调用，并将核实后的供应商订单同步到两类预订记录。 */
@Component("bookingPersistenceHook")
public class BookingPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(BookingPersistenceHook.class);
    private static final String SHELL_TOOL_NAME = "execute_shell_command";
    private static final String PLATFORM_TUNIU = "tuniu-cli";
    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";
    private static final String CMD_FLIGHT_CREATE = "call flight saveOrder";
    private static final String CMD_HOTEL_CREATE = "call hotel tuniuHotelCreateOrder";
    private static final String CMD_TRAIN_CREATE = "call train bookTrain";
    private static final String CMD_FLIGHT_CANCEL = "call flight cancelOrder";
    private static final String CMD_TRAIN_CANCEL = "call train cancelOrder";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "idnumber", "idcard", "idcardno", "psptid", "psptname", "passportno", "certificateno",
            "mobile", "mobilephone", "phone", "telephone", "tel", "contactname", "contactphone",
            "contactmobile", "contacttourist", "tourist", "tourists", "adulttourists", "passenger",
            "passengers", "traveler", "travelers", "children", "contact");

    private final TripBookingRecordService tripBookingRecordService;
    private final BookingRecordService bookingRecordService;
    private final SseEmitterRegistry emitterRegistry;

    /** 创建不发送 SSE 结果的 Hook，便于单元测试和无 Web 会话场景复用。 */
    public BookingPersistenceHook(TripBookingRecordService tripBookingRecordService,
                                  BookingRecordService bookingRecordService) {
        this(tripBookingRecordService, bookingRecordService, null);
    }

    /** 创建完整的预订持久化 Hook。 */
    @Autowired
    public BookingPersistenceHook(TripBookingRecordService tripBookingRecordService,
                                  BookingRecordService bookingRecordService,
                                  SseEmitterRegistry emitterRegistry) {
        this.tripBookingRecordService = tripBookingRecordService;
        this.bookingRecordService = bookingRecordService;
        this.emitterRegistry = emitterRegistry;
    }

    /** 将副作用 Hook 排在凭证注入和 Shell 校验之后执行。 */
    @Override
    public int priority() {
        return 20;
    }

    /** 处理工具完成事件，任何持久化异常都不得中断主 Agent 流程。 */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent postActing)) {
            return Mono.just(event);
        }
        return Mono.deferContextual(context -> {
            try {
                handlePostActing(postActing, context);
            } catch (Exception exception) {
                logger.error("[BookingPersistence] 预订结果同步异常", exception);
            }
            return Mono.just(event);
        });
    }

    /** 过滤 Shell 工具调用并按命令类型分派创建或取消逻辑。 */
    private void handlePostActing(PostActingEvent event, ContextView context) {
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null || !SHELL_TOOL_NAME.equals(toolUse.getName()) || toolUse.getInput() == null) {
            return;
        }
        Object commandValue = toolUse.getInput().get("command");
        if (!(commandValue instanceof String command) || command.isBlank()) {
            return;
        }
        BookingOp operation = resolveOp(command);
        if (operation == null) {
            return;
        }

        AgentRuntimeContext runtimeContext = context.getOrDefault(RUNTIME_CONTEXT_KEY, null);
        if (runtimeContext == null || runtimeContext.getUserId() == null
                || runtimeContext.getUserId().isBlank()) {
            logger.warn("[BookingPersistence] 缺少用户运行时上下文，跳过预订同步");
            return;
        }

        // 解析成一个JSON对象
        JSONObject response = parseJson(extractResultText(event.getToolResult()));
        if (operation.action() == Action.CANCEL) {
            // 取消操作（取消操作Hook机制保留, 后续会用工具去进行取消操作）
            handleCancel(runtimeContext, operation.bizType(), command, response);
        } else {
            // 创建操作
            handleCreate(runtimeContext, operation.bizType(), command, response);
        }
    }

    /** 根据供应商结果创建正式订单，通过平台、业务类型和外部单号避免重复落库。 */
    private void handleCreate(AgentRuntimeContext runtimeContext, String bizType,
                              String command, JSONObject response) {
        // 1. 仅在操作成功时同步
        if (Boolean.FALSE.equals(response.getBoolean("success"))) {
            logger.info("[BookingPersistence] 操作未成功，跳过同步, op={}, bizType={}", "CREATE", bizType);
            return;
        }

        // 业务结果通常在 result 内层，取不到则退回外层
        JSONObject result = response.getJSONObject("result");
        if (result == null) {
            result = response;
        }

        // MCP 工具响应会把真实业务 JSON 以字符串形式包在 result.content[].text 中，需先解包
        result = unwrapMcpContent(result);


        // 部分平台接口（如火车票 bookTrain）将业务数据再包一层 data，需穿透
        JSONObject data = result != null ? result.getJSONObject("data") : null;
        JSONObject orderSource = (data != null && !data.isEmpty()) ? data : result;

        // 提取外部单号
        String externalOrderNo = firstString(orderSource,
                "orderId", "orderNo", "mainOrderNo", "bookingOrderId", "order_id");

        // 幂等：同平台 + 类型 + 外部单号已存在则不重复落库
        if (bookingRecordService.findByExternalOrder(PLATFORM_TUNIU, bizType, externalOrderNo).isPresent()) {
            logger.info("[BookingPersistence] 预订记录已存在，跳过重复落库, orderNo={}", externalOrderNo);
            return;
        }
        String userId = runtimeContext.getUserId().trim();
        String sessionId = runtimeContext.getSessionId();
        JSONObject arguments = parseArgs(command);

        BookingRecordEntity bookingRecord = new BookingRecordEntity();
        bookingRecord.setUserId(userId);
        bookingRecord.setConversationId(sessionId);
        bookingRecord.setExternalOrderNo(externalOrderNo); // 平台侧返回的订单号
        bookingRecord.setBookingId(UUID.randomUUID().toString()); // 预定订单号（我们应用侧去生成, 后续改用雪花算法）
        bookingRecord.setBizType(BookingType.valueOf(bizType));
        // 平台参与唯一键查询和新增参数校验，落库值必须与查询条件保持一致。
        bookingRecord.setPlatform(PLATFORM_TUNIU);
        bookingRecord.setBookedAt(LocalDateTime.now()); // 执行预定的时间
        bookingRecord.setStatus("CREATED"); // 创建状态
        bookingRecord.setPaymentStatus("UNPAID"); // 未支付状态
        // ========== 联系人 和 联系电话 和 出发时间 这些参数应该尽量从CLI命令-a的入参去补全 ========
        enrichFromArgs(bookingRecord, bizType, arguments);

        // detail 保留入参与结果全量，便于追溯
        JSONObject detail = new JSONObject();
        detail.put("args", arguments);
        detail.put("result", result);
        bookingRecord.setDetail(detail.toJSONString());

        // 保存到数据库中
        bookingRecordService.save(bookingRecord);
        logger.info("[BookingPersistence] 预订记录已落库, userId={}, bizType={}, platform={}, orderNo={}, bookingId={}",
                userId, bizType, PLATFORM_TUNIU, externalOrderNo, bookingRecord.getBookingId());

        // 下单成功后向前端推送结构化支付事件（含订单号与支付链接），供渲染"立即支付"卡片
        emitBookingResult(sessionId, bookingRecord, orderSource);
    }

    /** 处理供应商取消成功结果，并同步正式订单和差旅预占记录。 */
    private void handleCancel(AgentRuntimeContext runtimeContext, String bizType,
                              String command, JSONObject response) {
        // 取消逻辑, 暂不实现
    }

    /** 根据途牛命令识别预订业务类型和操作类型。 */
    BookingOp resolveOp(String command) {
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (normalized.contains(CMD_FLIGHT_CREATE.toLowerCase(Locale.ROOT))) {
            return new BookingOp(Action.CREATE, "FLIGHT");
        }
        if (normalized.contains(CMD_HOTEL_CREATE.toLowerCase(Locale.ROOT))) {
            return new BookingOp(Action.CREATE, "HOTEL");
        }
        if (normalized.contains(CMD_TRAIN_CREATE.toLowerCase(Locale.ROOT))) {
            return new BookingOp(Action.CREATE, "TRAIN");
        }
        if (normalized.contains(CMD_FLIGHT_CANCEL.toLowerCase(Locale.ROOT))) {
            return new BookingOp(Action.CANCEL, "FLIGHT");
        }
        if (normalized.contains(CMD_TRAIN_CANCEL.toLowerCase(Locale.ROOT))) {
            return new BookingOp(Action.CANCEL, "TRAIN");
        }
        return null;
    }

    /** 预订 Hook 支持的操作类型。 */
    enum Action { CREATE, CANCEL }

    /** 供应商命令识别结果。 */
    record BookingOp(Action action, String bizType) {
    }

    /** 从供应商订单参数补全标题、时间、联系人和金额。 */
    void enrichFromArgs(BookingRecordEntity form, String bizType, JSONObject arguments) {
        if (form == null || arguments == null) {
            return;
        }
        switch (bizType) {
            case "FLIGHT" -> {
                form.setTitle(joinNonBlank(" ",
                        joinNonBlank("→", arguments.getString("departureCityName"),
                                arguments.getString("arrivalCityName")), arguments.getString("flightNo")));
                form.setStartTime(toDateTime(firstString(arguments, "departureDate", "departureTime")));
                JSONObject contact = arguments.getJSONObject("contactTourist");
                if (contact != null) {
                    form.setContactName(firstString(contact, "name", "chineseName"));
                    form.setContactPhone(firstString(contact, "mobile", "phone", "tel"));
                }
            }
            case "HOTEL" -> {
                form.setTitle(firstString(arguments, "hotelName", "hotel_name"));
                if (form.getTitle() == null) {
                    form.setTitle("酒店预订");
                }
                form.setStartTime(toDateTime(arguments.getString("checkInDate")));
                form.setEndTime(toDateTime(arguments.getString("checkOutDate")));
                form.setContactName(arguments.getString("contactName"));
                form.setContactPhone(firstString(arguments, "contactPhone", "mobile", "phone"));
            }
            case "TRAIN" -> {
                form.setTitle(joinNonBlank(" ", arguments.getString("departureCityName"),
                        arguments.getString("arrivalCityName"), firstString(arguments, "trainNum", "trainNo")));
                if (form.getTitle() == null) {
                    form.setTitle("火车票预订");
                }
                JSONArray resources = arguments.getJSONArray("resources");
                if (resources != null && !resources.isEmpty() && resources.getJSONObject(0) != null) {
                    JSONObject first = resources.getJSONObject(0);
                    form.setStartTime(toDateTime(firstString(first, "departsDate", "departureDate")));
                    form.setTotalAmount(first.getBigDecimal("adultPrice"));
                }
                JSONObject contact = arguments.getJSONObject("contact");
                if (contact != null) {
                    form.setContactPhone(firstString(contact, "tel", "mobile", "phone"));
                }
            }
            default -> {
                // 当前途牛 Hook 仅覆盖命令识别器列出的业务类型。
            }
        }
        if (form.getTotalAmount() == null) {
            form.setTotalAmount(findDecimal(arguments, "totalAmount", "amount", "price"));
        }
    }


    /** 递归检查响应中是否存在明确失败标志。 */
    private boolean hasExplicitFailure(JSONObject root) {
        return hasBoolean(root, "success", false) || hasBoolean(root, "successCode", false);
    }

    /** 递归读取指定布尔字段的目标值。 */
    private boolean hasBoolean(Object value, String key, boolean expected) {
        if (value instanceof JSONObject object) {
            Object field = object.get(key);
            if (field instanceof Boolean bool && bool == expected) {
                return true;
            }
            for (Object child : object.values()) {
                if (hasBoolean(child, key, expected)) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                if (hasBoolean(child, key, expected)) {
                    return true;
                }
            }
        } else if (value instanceof String text && JSON.isValidObject(text)) {
            return hasBoolean(JSON.parseObject(text), key, expected);
        }
        return false;
    }

    /** 递归读取第一个非空字段，兼容数字订单号和 MCP 字符串 JSON。 */
    String firstStringDeep(Object value, String... keys) {
        if (value instanceof JSONObject object) {
            String direct = firstString(object, keys);
            if (direct != null) {
                return direct;
            }
            for (Object child : object.values()) {
                String nested = firstStringDeep(child, keys);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object child : array) {
                String nested = firstStringDeep(child, keys);
                if (nested != null) {
                    return nested;
                }
            }
        } else if (value instanceof String text && JSON.isValidObject(text)) {
            return firstStringDeep(JSON.parseObject(text), keys);
        }
        return null;
    }

    /**
     * 向前端推送 {@code booking_result} SSE 事件：包含业务类型、订单号、标题、金额与支付链接。
     * 前端据此渲染带“立即支付”按钮的支付卡片，行为确定、不依赖 LLM 复述链接。
     *
     * <p>事件 JSON 格式：
     * <pre>
     * {
     *   "type": "booking_result",
     *   "bizType": "flight",
     *   "bizTypeLabel": "机票",
     *   "orderId": "外部订单号",
     *   "title": "北京→上海 MU5101",
     *   "amount": "1280.00",
     *   "payUrl": "https://...",
     *   "statusLabel": "待支付"
     * }
     * </pre>
     */
    private void emitBookingResult(String sessionId, BookingRecordEntity record, JSONObject result) {
        if (sessionId == null) {
            return;
        }
        SseEmitter emitter = emitterRegistry.getEmitter(sessionId);
        if (emitter == null) {
            return;
        }

        // 支付/详情链接：容错多种字段命名
        String payUrl = firstString(result,
                "payUrl", "paymentUrl", "cashierUrl", "orderDetailH5Url", "orderDetailUrl", "jumpUrl");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "booking_result");
        data.put("bizType", record.getBizType() != null ? record.getBizType().name() : "unknown");
        data.put("bizTypeLabel", record.getBizType() != null ? record.getBizType().getLabel() : "预订");
        data.put("orderId", record.getExternalOrderNo());
        data.put("title", record.getTitle());
        data.put("amount", record.getTotalAmount() != null ? record.getTotalAmount().toPlainString() : null);
        data.put("currency", record.getCurrency());
        data.put("payUrl", payUrl);
        data.put("statusLabel", "待支付");

        try {
            emitter.send(SseEmitter.event().name("booking_result").data(JSON.toJSONString(data)));
            logger.info("[BookingPersistence] 已推送 booking_result 事件, sessionId={}, orderNo={}, payUrl={}",
                    sessionId, record.getExternalOrderNo(), payUrl != null ? "有" : "无");
        } catch (IllegalStateException e) {
            logger.debug("[BookingPersistence] booking_result 发送失败，emitter 已完成: {}", e.getMessage());
        } catch (IOException e) {
            logger.debug("[BookingPersistence] booking_result 发送失败（网络异常）: {}", e.getMessage());
        }
    }

    /** 从 Shell 命令中截取参数 JSON，并兼容 Windows 转义的双引号。 */
    JSONObject parseArgs(String command) {
        if (command == null) {
            return null;
        }
        int start = command.indexOf('{');
        int end = command.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String raw = command.substring(start, end + 1);
        JSONObject parsed = parseJson(raw);
        return parsed != null ? parsed : parseJson(raw.replace("\\\"", "\""));
    }

    /** 提取工具结果中的文本块，忽略挂起或无法序列化的内容块。 */
    private String extractResultText(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.isSuspended() || toolResult.getOutput() == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : toolResult.getOutput()) {
            try {
                JSONObject json = JSON.parseObject(JSON.toJSONString(block));
                String text = json.getString("text");
                if (text != null && !text.isBlank()) {
                    if (!result.isEmpty()) {
                        result.append('\n');
                    }
                    result.append(text.trim());
                }
            } catch (Exception exception) {
                logger.debug("[BookingPersistence] 忽略无法解析的工具结果块: {}", exception.getMessage());
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    /** 从 CLI 输出或 XML 标签文本中提取并解析 JSON 对象。 */
    JSONObject parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        try {
            return JSON.parseObject(normalized);
        } catch (Exception ignored) {
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return JSON.parseObject(normalized.substring(start, end + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }

    /** 从对象的多个候选字段中读取第一个非空文本。 */
    String firstString(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            Object value = object.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    /** 解包 MCP content 中的对象或字符串化 JSON。 */
    JSONObject unwrapMcpContent(JSONObject result) {
        if (result == null) {
            return null;
        }
        JSONArray content = result.getJSONArray("content");
        if (content == null) {
            return result;
        }
        for (Object item : content) {
            if (item instanceof JSONObject object) {
                Object text = object.get("text");
                if (text instanceof JSONObject nested) {
                    return nested;
                }
                if (text instanceof String textValue) {
                    JSONObject nested = parseJson(textValue);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return result;
    }

    /** 递归移除明细中的证件、联系方式及乘客对象。 */
    private Object sanitize(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject copy = new JSONObject();
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                String normalizedKey = entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
                if (!SENSITIVE_KEYS.contains(normalizedKey)) {
                    copy.put(entry.getKey(), sanitize(entry.getValue()));
                }
            }
            return copy;
        }
        if (value instanceof String text && JSON.isValidObject(text)) {
            JSONObject parsed = JSON.parseObject(text);
            return sanitize(parsed).toString();
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (Object item : array) {
                copy.add(sanitize(item));
            }
            return copy;
        }
        return value;
    }

    /** 从嵌套对象查找并解析金额字段。 */
    private BigDecimal findDecimal(JSONObject object, String... keys) {
        String value = firstStringDeep(object, keys);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 将途牛日期字符串转换为业务记录使用的本地时间。 */
    private LocalDateTime toDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"), DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                if (formatter == DateTimeFormatter.ISO_LOCAL_DATE) {
                    return LocalDate.parse(text, formatter).atStartOfDay();
                }
                return LocalDateTime.parse(text, formatter);
            } catch (Exception ignored) {
                // 继续尝试途牛返回的其他日期格式。
            }
        }
        return null;
    }

    /** 按给定分隔符拼接非空文本片段。 */
    private String joinNonBlank(String separator, String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!result.isEmpty()) {
                    result.append(separator);
                }
                result.append(value.trim());
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    /** 供应商响应的归一化结果。 */
    private record ProviderResult(boolean success, String orderNo, String status,
                                  JSONObject businessObject, boolean definitivelyFailed) {
    }
}

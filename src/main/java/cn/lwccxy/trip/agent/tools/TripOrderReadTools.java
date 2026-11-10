package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalRecordQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderQueryForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
import cn.lwccxy.trip.business.trip.enums.TripOrderStatus;
import cn.lwccxy.trip.business.trip.service.TripApprovalRecordService;
import cn.lwccxy.trip.business.trip.service.TripOrderService;
import cn.lwccxy.trip.common.exception.BizException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 提供行程单管理子智能体的只读工具。 */
@Component
public class TripOrderReadTools {

    private static final Logger logger = LoggerFactory.getLogger(TripOrderReadTools.class);
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_DESTINATION_LENGTH = 256;
    private static final int MAX_DEPARTURE_CITY_LENGTH = 128;

    private final TripOrderService tripOrderService;
    private final TripApprovalRecordService approvalRecordService;

    /** 创建行程单只读工具。 */
    public TripOrderReadTools(TripOrderService tripOrderService,
                              TripApprovalRecordService approvalRecordService) {
        this.tripOrderService = tripOrderService;
        this.approvalRecordService = approvalRecordService;
    }

    /** 查询当前认证用户可见的出差申请单列表，并将分页结果转换为工具专用结构。 */
    @Tool(name = "query_trip_orders", description = "查询当前登录用户自己的出差申请单。支持按状态、目的地、出发城市、出发日期和返回日期筛选并分页。pageNo 默认 1，pageSize 默认 20，范围 1 到 100。")
    public String queryTripOrders(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "status", description = "可选状态：DRAFT、SUBMITTED、APPROVED、REJECTED、COMPLETED、CANCELLED", required = false) String status,
            @ToolParam(name = "destination", description = "可选目的地，最多 256 个字符", required = false) String destination,
            @ToolParam(name = "departureCity", description = "可选出发城市，最多 128 个字符", required = false) String departureCity,
            @ToolParam(name = "departureDate", description = "可选出发日期，格式 YYYY-MM-DD", required = false) String departureDate,
            @ToolParam(name = "returnDate", description = "可选返回日期，格式 YYYY-MM-DD", required = false) String returnDate,
            @ToolParam(name = "pageNo", description = "可选页码，从 1 开始，默认 1", required = false) Long pageNo,
            @ToolParam(name = "pageSize", description = "可选每页条数，范围 1 到 100，默认 20", required = false) Long pageSize) {
        String queriedAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(sessionContext);
            String normalizedStatus = normalize(status);
            String normalizedDestination = normalize(destination);
            String normalizedDepartureCity = normalize(departureCity);
            String normalizedDepartureDate = normalize(departureDate);
            String normalizedReturnDate = normalize(returnDate);
            PageInput page = validateListInput(normalizedStatus, normalizedDestination, normalizedDepartureCity,
                    normalizedDepartureDate, normalizedReturnDate, pageNo, pageSize);

            TripOrderQueryForm form = new TripOrderQueryForm();
            form.setStatus(normalizedStatus);
            form.setDestination(normalizedDestination);
            form.setDepartureCity(normalizedDepartureCity);
            form.setDepartureDate(normalizedDepartureDate);
            form.setReturnDate(normalizedReturnDate);
            form.setPageNo(page.pageNo());
            form.setPageSize(page.pageSize());

            // 服务层负责最终的归属过滤和数据库查询，工具只负责把自然语言参数转成合法查询条件。
            var response = tripOrderService.list(userId, form);
            List<TripOrderVO> orders = response.getData() == null ? List.of() : response.getData();
            String message = orders.isEmpty() ? "没有找到符合条件的出差申请单，请调整目的地、日期或状态后重试。" : "查询成功。";
            return toJson(new TripOrderListResult(true, null, message, orders,
                    new PageResult(response.getPageNo(), response.getPageSize(), response.getTotalCount(), response.getTotalPage()),
                    "trip_domain_service", queriedAt));
        } catch (BizException exception) {
            return toJson(queryError(exception, "查询出差申请单"));
        } catch (IllegalArgumentException exception) {
            return toJson(new TripOrderListResult(false, "INVALID_PARAMETER", exception.getMessage(),
                    List.of(), null, "trip_domain_service", queriedAt));
        } catch (Exception exception) {
            logger.error("[TOOL][query_trip_orders] 查询失败", exception);
            return toJson(new TripOrderListResult(false, "INTERNAL_ERROR", "查询出差申请单时发生系统异常，请稍后重试。",
                    List.of(), null, "trip_domain_service", queriedAt));
        }
    }

    /** 查询当前认证用户所属的单个出差申请单。 */
    @Tool(name = "get_trip_order_detail", description = "根据申请单 ID 查询当前登录用户自己的申请单详情。tripOrderId 必填，不能为空，最长 64 个字符。")
    public String getTripOrderDetail(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "tripOrderId", description = "必填，出差申请单 ID") String tripOrderId) {
        String queriedAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(sessionContext);
            String orderId = requireText(tripOrderId, "tripOrderId", "请提供要查询的出差申请单 ID。", MAX_ID_LENGTH);

            // 领域服务会同时校验申请单存在性和当前用户归属，工具不直接访问 Mapper。
            TripOrderVO order = tripOrderService.get(userId, orderId).getData();
            return toJson(new TripOrderDetailResult(true, null, "查询成功。", order,
                    "trip_domain_service", queriedAt));
        } catch (BizException exception) {
            return toJson(new TripOrderDetailResult(false, exception.getErrorCode(),
                    friendlyMessage(exception, "查询出差申请单详情"), null, "trip_domain_service", queriedAt));
        } catch (IllegalArgumentException exception) {
            return toJson(new TripOrderDetailResult(false, "INVALID_PARAMETER", exception.getMessage(),
                    null, "trip_domain_service", queriedAt));
        } catch (Exception exception) {
            logger.error("[TOOL][get_trip_order_detail] 查询失败", exception);
            return toJson(new TripOrderDetailResult(false, "INTERNAL_ERROR", "查询出差申请单详情时发生系统异常，请稍后重试。",
                    null, "trip_domain_service", queriedAt));
        }
    }

    /** 检查当前用户指定出差申请单的行程日期是否仍可用于规划。 */
    @Tool(name = "check_trip_time_validity", description = "检查当前登录用户指定出差申请单的日期是否仍有效。返回 valid=true 表示行程尚未开始且未结束；出发日期当天及之后、或返回日期之后均返回 valid=false。")
    public String checkTripTimeValidity(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "tripOrderId", description = "必填，出差申请单 ID") String tripOrderId) {
        String checkedAt = OffsetDateTime.now().toString();
        LocalDate today = LocalDate.now();
        try {
            String userId = requireUserId(sessionContext);
            String orderId = requireText(tripOrderId, "tripOrderId", "请提供要检查的出差申请单 ID。", MAX_ID_LENGTH);

            // 通过领域服务读取并校验单据归属，避免工具绕过当前用户的数据访问边界。
            TripOrderVO order = tripOrderService.get(userId, orderId).getData();

            if(order != null){
                // 如果存在出差申请单相关信息, 就缓存起来
                sessionContext.setCacheOrder(order);
            }

            // =============== 检查当前订单是否已经过时 ============
            String blockReason = evaluateTimeBlockReason(order, today);
            List<BlockedTripOrder> blockedOrders = blockReason == null
                    ? List.of()
                    : List.of(new BlockedTripOrder(order.getId(), order.getDestination(),
                    order.getDepartureDate(), order.getReturnDate(), blockReason));

            boolean valid = blockedOrders.isEmpty();
            String message = valid ? "差旅单日期有效，可继续规划。"
                    : "检测到行程时间无效，无法继续规划。" + blockReason;

            return toTimeValidityJson(new TripOrderTimeValidityResult(true, null, today.toString(), valid, message,
                    blockedOrders, "trip_domain_service", checkedAt));
        } catch (BizException exception) {
            return toTimeValidityJson(new TripOrderTimeValidityResult(false, exception.getErrorCode(), today.toString(),
                    null, friendlyMessage(exception, "检查出差申请单时间"), List.of(),
                    "trip_domain_service", checkedAt));
        } catch (IllegalArgumentException exception) {
            return toTimeValidityJson(new TripOrderTimeValidityResult(false, "INVALID_PARAMETER", today.toString(),
                    null, exception.getMessage(), List.of(), "trip_domain_service", checkedAt));
        } catch (Exception exception) {
            logger.error("[TOOL][check_trip_time_validity] 检查失败", exception);
            return toTimeValidityJson(new TripOrderTimeValidityResult(false, "INTERNAL_ERROR", today.toString(),
                    null, "检查出差申请单时间时发生系统异常，请稍后重试。", List.of(),
                    "trip_domain_service", checkedAt));
        }
    }

    /** 安全序列化时间校验结果，确保工具异常时仍返回可解析的 JSON。 */
    private static String toTimeValidityJson(TripOrderTimeValidityResult result) {
        try {
            String json = toJson(result);
            return json == null ? "{\"success\":false,\"errorCode\":\"INTERNAL_ERROR\",\"message\":\"工具结果序列化失败。\"}" : json;
        } catch (Exception exception) {
            return "{\"success\":false,\"errorCode\":\"INTERNAL_ERROR\",\"message\":\"工具结果序列化失败。\"}";
        }
    }

    /** 判断行程是否已经开始、结束，或包含无法识别的日期。 */
    private static String evaluateTimeBlockReason(TripOrderVO order, LocalDate today) {
        try {
            if (hasText(order.getReturnDate())) {
                LocalDate returnDate = LocalDate.parse(order.getReturnDate());
                if (today.isAfter(returnDate)) {
                    return "行程已结束（返回日期 " + order.getReturnDate() + " 已过）。";
                }
            }
            if (hasText(order.getDepartureDate())) {
                LocalDate departureDate = LocalDate.parse(order.getDepartureDate());
                if (!today.isBefore(departureDate)) {
                    return "行程已开始（出发日期 " + order.getDepartureDate() + " 已到达或已过）。";
                }
            }
        } catch (DateTimeParseException exception) {
            // 历史数据若不符合 YYYY-MM-DD，必须阻止规划并提示修正，不能误判为有效。
            return "行程日期格式无效，请核对出发日期和返回日期。";
        }
        return null;
    }

    /** 查询申请单关联的审批记录，或按审批记录 ID 查询当前用户所属记录。 */
    @Tool(name = "get_trip_approval_record", description = "查询当前登录用户自己的审批记录。tripOrderId 和 approvalId 必须二选一，不能同时提供，也不能都缺失。")
    public String getTripApprovalRecord(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "tripOrderId", description = "与 approvalId 二选一，出差申请单 ID", required = false) String tripOrderId,
            @ToolParam(name = "approvalId", description = "与 tripOrderId 二选一，审批记录 ID", required = false) String approvalId) {
        String queriedAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(sessionContext);
            String orderId = normalize(tripOrderId);
            String recordId = normalize(approvalId);
            if (orderId == null && recordId == null) {
                throw new IllegalArgumentException("缺少查询目标：请提供 tripOrderId 或 approvalId 其中一个。");
            }
            if (orderId != null && recordId != null) {
                throw new IllegalArgumentException("查询目标不明确：tripOrderId 和 approvalId 只能提供一个，请删除其中一个。");
            }
            validateLength(orderId, "tripOrderId", MAX_ID_LENGTH);
            validateLength(recordId, "approvalId", MAX_ID_LENGTH);

            if (recordId != null) {
                // 按审批 ID 查询时，服务层仍按当前用户过滤，避免越权读取其他用户的审批记录。
                TripApprovalRecordVO record = approvalRecordService.get(userId, recordId).getData();
                return toJson(new TripApprovalResult(true, null, "查询成功。", "BY_APPROVAL_ID",
                        List.of(record), "trip_domain_service", queriedAt));
            }

            TripApprovalRecordQueryForm form = new TripApprovalRecordQueryForm();
            form.setOrderId(orderId);
            form.setPageNo(1L);
            form.setPageSize(20L);
            var response = approvalRecordService.list(userId, form);
            List<TripApprovalRecordVO> records = response.getData() == null ? List.of() : response.getData();
            String message = records.isEmpty() ? "没有找到该申请单关联的审批记录，请确认申请单 ID 或联系管理员核对。" : "查询成功。";
            return toJson(new TripApprovalResult(true, null, message, "BY_TRIP_ORDER_ID", records,
                    "trip_domain_service", queriedAt));
        } catch (BizException exception) {
            return toJson(new TripApprovalResult(false, exception.getErrorCode(),
                    friendlyMessage(exception, "查询审批记录"), "UNKNOWN", List.of(), "trip_domain_service", queriedAt));
        } catch (IllegalArgumentException exception) {
            return toJson(new TripApprovalResult(false, "INVALID_PARAMETER", exception.getMessage(),
                    "UNKNOWN", List.of(), "trip_domain_service", queriedAt));
        } catch (Exception exception) {
            logger.error("[TOOL][get_trip_approval_record] 查询失败", exception);
            return toJson(new TripApprovalResult(false, "INTERNAL_ERROR", "查询审批记录时发生系统异常，请稍后重试。",
                    "UNKNOWN", List.of(), "trip_domain_service", queriedAt));
        }
    }

    /** 校验列表查询参数，并返回补齐默认值后的分页参数。 */
    private static PageInput validateListInput(String status, String destination, String departureCity,
                                               String departureDate, String returnDate, Long pageNo, Long pageSize) {
        validateLength(destination, "destination", MAX_DESTINATION_LENGTH);
        validateLength(departureCity, "departureCity", MAX_DEPARTURE_CITY_LENGTH);
        validateStatus(status);
        validateDate(departureDate, "departureDate");
        validateDate(returnDate, "returnDate");
        if (departureDate != null && returnDate != null
                && LocalDate.parse(departureDate).isAfter(LocalDate.parse(returnDate))) {
            throw new IllegalArgumentException("日期范围无效：departureDate 不能晚于 returnDate，请重新提供日期。");
        }
        long normalizedPageNo = pageNo == null ? 1L : pageNo;
        long normalizedPageSize = pageSize == null ? 20L : pageSize;
        if (normalizedPageNo < 1 || normalizedPageNo > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pageNo 无效：必须是 1 到 " + Integer.MAX_VALUE + " 之间的整数。");
        }
        if (normalizedPageSize < 1 || normalizedPageSize > 100) {
            throw new IllegalArgumentException("pageSize 无效：必须是 1 到 100 之间的整数。");
        }
        return new PageInput(normalizedPageNo, normalizedPageSize);
    }

    /** 校验当前会话是否包含认证用户。 */
    private static String requireUserId(AgentRuntimeContext sessionContext) {
        if (sessionContext == null || !hasText(sessionContext.getUserId())) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，无法查询行程。请重新登录后再试。");
        }
        return sessionContext.getUserId().trim();
    }

    /** 校验必填文本并返回去除首尾空白后的值。 */
    private static String requireText(String value, String fieldName, String missingMessage, int maxLength) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(missingMessage);
        }
        validateLength(normalized, fieldName, maxLength);
        return normalized;
    }

    /** 校验文本字段长度，错误信息直接说明字段和允许范围。 */
    private static void validateLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 过长：最多允许 " + maxLength + " 个字符，请缩短后重试。");
        }
    }

    /** 校验状态枚举，避免非法状态进入服务层后只返回笼统错误。 */
    private static void validateStatus(String status) {
        if (status == null) {
            return;
        }
        try {
            TripOrderStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status 无效：可选值为 DRAFT、SUBMITTED、APPROVED、REJECTED、COMPLETED、CANCELLED。");
        }
    }

    /** 校验日期格式和真实日期值。 */
    private static void validateDate(String date, String fieldName) {
        if (date == null) {
            return;
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " 无效：必须使用 YYYY-MM-DD 格式，例如 2026-11-03。");
        }
    }

    /** 将领域异常转换为适合模型理解的查询错误。 */
    private static TripOrderListResult queryError(BizException exception, String operation) {
        return new TripOrderListResult(false, exception.getErrorCode(), friendlyMessage(exception, operation),
                List.of(), null, "trip_domain_service", OffsetDateTime.now().toString());
    }

    /** 将领域错误码翻译为下一步可执行的中文提示。 */
    private static String friendlyMessage(BizException exception, String operation) {
        if (TripErrorCode.INVALID_PARAMETER.getErrorCode().equals(exception.getErrorCode())) {
            return operation + "参数无效，请检查必填字段、状态和分页范围后重试。";
        }
        if (TripErrorCode.RESOURCE_NOT_FOUND.getErrorCode().equals(exception.getErrorCode())) {
            return "没有找到对应记录，或该记录不属于当前登录用户。请核对 ID 后重试。";
        }
        return operation + "失败，请稍后重试。";
    }

    /** 统一序列化工具返回 record。 */
    private static String toJson(Object result) {
        return JSON.toJSONString(result);
    }

    /** 归一化可选文本，空白内容按未提供处理。 */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 判断文本参数是否包含有效内容。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 工具列表查询专用返回结构。 */
    public record TripOrderListResult(boolean success, String errorCode, String message,
                                      List<TripOrderVO> orders, PageResult page,
                                      String source, String queriedAt) {
    }

    /** 工具详情查询专用返回结构。 */
    public record TripOrderDetailResult(boolean success, String errorCode, String message,
                                        TripOrderVO order, String source, String queriedAt) {
    }

    /** 工具审批查询专用返回结构。 */
    public record TripApprovalResult(boolean success, String errorCode, String message,
                                     String queryMode, List<TripApprovalRecordVO> records,
                                     String source, String queriedAt) {
    }

    /** 差旅单时间有效性检查专用返回结构。 */
    public record TripOrderTimeValidityResult(boolean success, String errorCode, String today,
                                              Boolean valid, String message,
                                              @JSONField(name = "blocked_orders")
                                              List<BlockedTripOrder> blockedOrders,
                                              String source, String checkedAt) {
    }

    /** 因时间原因不能用于规划的差旅单信息。 */
    public record BlockedTripOrder(@JSONField(name = "order_id") String orderId,
                                   String destination,
                                   @JSONField(name = "departure_date") String departureDate,
                                   @JSONField(name = "return_date") String returnDate,
                                   String reason) {
    }

    /** 列表工具专用分页信息。 */
    public record PageResult(long pageNo, long pageSize, long totalCount, long totalPage) {
    }

    /** 列表参数校验后的分页值。 */
    private record PageInput(long pageNo, long pageSize) {
    }
}

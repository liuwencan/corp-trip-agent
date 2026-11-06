package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderSaveForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
import cn.lwccxy.trip.business.trip.service.TripOrderService;
import cn.lwccxy.trip.common.exception.BizException;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/** 提供行程单管理子智能体的受控写入工具。 */
@Component
public class TripOrderWriteTools {

    private static final Logger logger = LoggerFactory.getLogger(TripOrderWriteTools.class);
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private final TripOrderService tripOrderService;

    /** 创建行程单写工具。 */
    public TripOrderWriteTools(TripOrderService tripOrderService) {
        this.tripOrderService = tripOrderService;
    }

    /** 创建当前认证用户的出差申请单，并同步进入管理员待审批队列。 */
    @Tool(name = "create_trip_application", description = "创建当前登录用户的出差申请单并同步创建管理员待审批记录。destination、departureCity、departureDate、returnDate、purpose 都是必填；日期格式为 YYYY-MM-DD，出发日期不能晚于返回日期。成功后申请状态为 SUBMITTED、审批状态为 PENDING。")
    public String createTripApplication(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "destination", description = "必填，目的地城市，最多 256 个字符") String destination,
            @ToolParam(name = "departureCity", description = "必填，出发城市，最多 128 个字符") String departureCity,
            @ToolParam(name = "departureDate", description = "必填，出发日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "returnDate", description = "必填，返回日期，格式 YYYY-MM-DD") String returnDate,
            @ToolParam(name = "purpose", description = "必填，出差事由，最多 512 个字符") String purpose,
            @ToolParam(name = "confirmConflict", description = "有日期冲突时必填 true，表示用户在了解冲突详情后明确选择继续；否则保持 false", required = false) Boolean confirmConflict) {
        String createdAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(sessionContext);
            String normalizedDestination = requireText(destination, "destination", "缺少 destination：请提供出差目的地城市。", 256);
            String normalizedDepartureCity = requireText(departureCity, "departureCity", "缺少 departureCity：请提供出发城市。", 128);
            String normalizedDepartureDate = requireDate(departureDate, "departureDate", "缺少 departureDate：请提供出发日期，格式为 YYYY-MM-DD。");
            String normalizedReturnDate = requireDate(returnDate, "returnDate", "缺少 returnDate：请提供返回日期，格式为 YYYY-MM-DD。");
            String normalizedPurpose = requireText(purpose, "purpose", "缺少 purpose：请说明本次出差事由。", 512);
            if (LocalDate.parse(normalizedDepartureDate).isAfter(LocalDate.parse(normalizedReturnDate))) {
                throw new IllegalArgumentException("日期范围无效：departureDate 不能晚于 returnDate，请重新提供出发和返回日期。");
            }

            // 创建入口重新检查有效行程，防止模型跳过预检或预检后已有行程发生变化。
            var conflicts = tripOrderService.findActiveOverlapping(userId, normalizedDepartureDate, normalizedReturnDate);
            if (!conflicts.isEmpty() && !Boolean.TRUE.equals(confirmConflict)) {
                LocalDate requestedDeparture = LocalDate.parse(normalizedDepartureDate);
                LocalDate requestedReturn = LocalDate.parse(normalizedReturnDate);
                var conflictPoints = conflicts.stream()
                        .map(order -> TripScheduleConflictTools.toConflictPoint(
                                order, requestedDeparture, requestedReturn))
                        .toList();
                return toJson(new CreateTripApplicationResult(false, "SCHEDULE_CONFLICT",
                        buildConflictMessage(conflictPoints),
                        null, null, "trip_domain_service", createdAt, conflictPoints));
            }

            TripOrderSaveForm form = new TripOrderSaveForm();
            form.setDestination(normalizedDestination);
            form.setDepartureCity(normalizedDepartureCity);
            form.setDepartureDate(normalizedDepartureDate);
            form.setReturnDate(normalizedReturnDate);
            form.setPurpose(normalizedPurpose);

            // 领域服务在同一事务内创建申请单和 PENDING 审批记录，避免申请与审批数据不一致。
            TripOrderService.TripApplicationCreation creation = tripOrderService.createWithApproval(userId, form);
            return toJson(new CreateTripApplicationResult(true, null,
                    "出差申请单已创建并提交审批队列，当前申请状态为 SUBMITTED，审批状态为 PENDING，等待管理员处理。",
                    creation.application(), creation.approval(),
                    "trip_domain_service", createdAt, java.util.List.of()));
        } catch (BizException exception) {
            return toJson(new CreateTripApplicationResult(false, exception.getErrorCode(),
                    friendlyMessage(exception), null, null, "trip_domain_service", createdAt, java.util.List.of()));
        } catch (IllegalArgumentException exception) {
            return toJson(new CreateTripApplicationResult(false, "INVALID_PARAMETER", exception.getMessage(),
                    null, null, "trip_domain_service", createdAt, java.util.List.of()));
        } catch (Exception exception) {
            // 工具边界将所有未知异常转换为稳定提示，避免模型误以为申请已创建成功。
            logger.error("[TOOL][create_trip_application] 创建申请单失败", exception);
            return toJson(new CreateTripApplicationResult(false, "INTERNAL_ERROR",
                    "创建出差申请单时发生系统异常，请稍后重试；本次结果不能视为创建成功。",
                    null, null, "trip_domain_service", createdAt, java.util.List.of()));
        }
    }

    /** 读取申请单当前状态并生成待用户确认的变更草案，不直接执行持久化。 */
    @Tool(name = "prepare_trip_order_change", description = "为已有出差申请单生成修改或取消草案。tripOrderId、operation、changeDescription 都是必填；operation 只能是 MODIFY 或 CANCEL。工具只生成草案，不直接修改数据库或供应商订单。")
    public String prepareTripOrderChange(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "tripOrderId", description = "必填，出差申请单 ID，最长 64 个字符") String tripOrderId,
            @ToolParam(name = "operation", description = "必填，操作类型：MODIFY 或 CANCEL") String operation,
            @ToolParam(name = "changeDescription", description = "必填，修改内容或取消原因，最多 1000 个字符") String changeDescription) {
        String preparedAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(sessionContext);
            String orderId = requireText(tripOrderId, "tripOrderId", "缺少 tripOrderId：请提供要修改或取消的出差申请单 ID。", MAX_ID_LENGTH);
            String normalizedOperation = requireText(operation, "operation", "缺少 operation：请说明是 MODIFY（修改）还是 CANCEL（取消）。", 16).toUpperCase(Locale.ROOT);
            String description = requireText(changeDescription, "changeDescription", "缺少 changeDescription：请说明要修改的内容或取消原因。", MAX_DESCRIPTION_LENGTH);
            if (!"MODIFY".equals(normalizedOperation) && !"CANCEL".equals(normalizedOperation)) {
                throw new IllegalArgumentException("operation 无效：只能填写 MODIFY（修改）或 CANCEL（取消）。");
            }

            // 生成草案前重新读取当前状态，确保模型展示的是服务端最新事实。
            TripOrderVO currentOrder = tripOrderService.get(userId, orderId).getData();
            TripOrderChangeDraft draft = new TripOrderChangeDraft(
                    UUID.randomUUID().toString().replace("-", ""),
                    normalizedOperation,
                    description,
                    currentOrder,
                    "WAITING_USER_CONFIRMATION",
                    false,
                    "草案已生成。请先向用户展示当前申请单、操作内容和可能影响，获得明确确认后再进入受控执行流程。",
                    "trip_domain_service",
                    preparedAt);
            return toJson(new TripOrderChangeResult(true, null, "变更草案生成成功。", draft));
        } catch (BizException exception) {
            return toJson(new TripOrderChangeResult(false, exception.getErrorCode(),
                    friendlyMessage(exception), null));
        } catch (IllegalArgumentException exception) {
            return toJson(new TripOrderChangeResult(false, "INVALID_PARAMETER", exception.getMessage(), null));
        } catch (Exception exception) {
            // 工具边界吞掉异常并返回稳定结构，避免异常堆栈直接暴露给模型或中断 Agent 推理。
            logger.error("[TOOL][prepare_trip_order_change] 生成草案失败", exception);
            return toJson(new TripOrderChangeResult(false, "INTERNAL_ERROR",
                    "生成变更草案时发生系统异常，请稍后重试；当前没有执行任何修改。", null));
        }
    }

    /** 校验当前会话是否包含认证用户。 */
    private static String requireUserId(AgentRuntimeContext sessionContext) {
        if (sessionContext == null || sessionContext.getUserId() == null || sessionContext.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，无法生成变更草案。请重新登录后再试。");
        }
        return sessionContext.getUserId().trim();
    }

    /** 校验必填文本并返回去除首尾空白后的值。 */
    private static String requireText(String value, String fieldName, String missingMessage, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(missingMessage);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 过长：最多允许 " + maxLength + " 个字符，请缩短后重试。");
        }
        return normalized;
    }

    /** 校验必填日期格式并返回归一化值。 */
    private static String requireDate(String value, String fieldName, String missingMessage) {
        String normalized = requireText(value, fieldName, missingMessage, 10);
        try {
            LocalDate.parse(normalized);
            return normalized;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " 无效：必须使用 YYYY-MM-DD 格式，例如 2026-11-03。");
        }
    }

    /** 将领域错误码翻译为模型下一步可执行的提示。 */
    private static String friendlyMessage(BizException exception) {
        if (TripErrorCode.RESOURCE_NOT_FOUND.getErrorCode().equals(exception.getErrorCode())) {
            return "没有找到对应申请单，或该申请单不属于当前登录用户。请核对 tripOrderId 后重试。";
        }
        if (TripErrorCode.INVALID_PARAMETER.getErrorCode().equals(exception.getErrorCode())) {
            return "申请单当前参数无效，请核对 tripOrderId 和当前业务状态后重试。";
        }
        return "生成变更草案失败，请稍后重试；当前没有执行任何修改。";
    }

    /** 将创建前发现的每条重叠行程整理成模型可以直接转述给用户的说明。 */
    private static String buildConflictMessage(
            java.util.List<TripScheduleConflictTools.ConflictPoint> conflicts) {
        String details = conflicts.stream()
                .map(conflict -> String.format("申请单 %s（%s至%s，状态 %s）与本次申请重叠 %s 至 %s",
                        conflict.tripOrderId(), conflict.existingDepartureDate(),
                        conflict.existingReturnDate(), conflict.status(),
                        conflict.overlapStartDate(), conflict.overlapEndDate()))
                .collect(java.util.stream.Collectors.joining("；"));
        return "发现已有行程日期冲突，申请尚未创建。冲突原因：" + details
                + "。建议修改本次申请的出发或返回日期，避开重叠区间；如果确需继续，必须先向用户说明以上冲突并取得明确同意。";
    }

    /** 统一序列化工具返回 record。 */
    private static String toJson(Object result) {
        return JSON.toJSONString(result);
    }

    /** 工具变更草案结果。 */
    public record TripOrderChangeResult(boolean success, String errorCode, String message,
                                        TripOrderChangeDraft draft) {
    }

    /** 工具变更草案内容。 */
    public record TripOrderChangeDraft(String draftId, String operation, String changeDescription,
                                       TripOrderVO currentOrder, String status,
                                       boolean executionSupported, String nextStep,
                                       String source, String preparedAt) {
    }

    /** 工具创建申请单及待审批记录的专用返回结构。 */
    public record CreateTripApplicationResult(boolean success, String errorCode, String message,
                                              TripOrderVO application,
                                              cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO approval,
                                              String source, String createdAt,
                                              java.util.List<TripScheduleConflictTools.ConflictPoint> conflicts) {
    }
}

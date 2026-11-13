package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordSaveForm;
import cn.lwccxy.trip.business.bookingrecord.service.BookingCancellationService;
import cn.lwccxy.trip.business.bookingrecord.service.BookingRecordService;
import cn.lwccxy.trip.common.exception.BizException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

/** 提供正式业务预订记录写入工具。 */
@Component
public class BookingRecordWriteTools {

    private static final Logger logger = LoggerFactory.getLogger(BookingRecordWriteTools.class);
    private final BookingRecordService service;
    private final BookingCancellationService cancellationService;

    /** 创建正式预订记录写入工具。 */
    public BookingRecordWriteTools(BookingRecordService service, BookingCancellationService cancellationService) {
        this.service = service;
        this.cancellationService = cancellationService;
    }

    /** 在供应商返回订单结果后创建正式预订记录。 */
    @Tool(name = "save_booking_record", description = "供应商订单已创建或确认后，保存正式 booking_record。recordJson 必须是 JSON 对象，至少包含 bizType 和 platform；不得写入其他用户的记录，userId 将由会话自动确定。成功后再向用户报告记录已保存。")
    public String save(AgentRuntimeContext context,
                       @ToolParam(name = "recordJson", description = "BookingRecordSaveForm JSON 字符串，包含业务类型、平台、订单状态及可用的订单详情") String recordJson) {
        String savedAt = OffsetDateTime.now().toString();
        try {
            String userId = currentUserId(context);
            if (userId == null) {
                return JSON.toJSONString(new ToolResult(false, "LOGIN_REQUIRED",
                        "当前会话缺少登录用户信息，请重新登录后再保存预订记录。", null, savedAt));
            }
            if (recordJson == null || !JSON.isValidObject(recordJson)) {
                return JSON.toJSONString(new ToolResult(false, "INVALID_PARAMETER",
                        "请将预订资料整理为 JSON 对象，并提供业务类型 bizType 和预订平台 platform 后重试。", null, savedAt));
            }
            BookingRecordSaveForm form = JSON.parseObject(recordJson, BookingRecordSaveForm.class);
            var result = service.create(userId, form);
            return JSON.toJSONString(new ToolResult(true, null, "正式预订记录已保存。", result.getData(), savedAt));
        } catch (BizException exception) {
            return JSON.toJSONString(new ToolResult(false, exception.getErrorCode(),
                    "预订资料未通过校验或订单已存在，请先查询记录，再核对业务类型、平台和供应商订单号。", null, savedAt));
        } catch (JSONException | IllegalArgumentException | jakarta.validation.ValidationException exception) {
            return JSON.toJSONString(new ToolResult(false, "INVALID_PARAMETER",
                    "预订资料格式不正确，请检查字段类型、日期格式、必填项和长度后重试。", null, savedAt));
        } catch (Exception exception) {
            // 不记录异常原文，防止数据库错误携带预订明细或个人信息。
            logger.error("[TOOL][save_booking_record] 保存失败，异常类型={}", exception.getClass().getSimpleName());
            return JSON.toJSONString(new ToolResult(false, "SAVE_FAILED", "正式预订记录保存失败，请勿将本次记录视为已保存。", null, savedAt));
        }
    }

    /**
     * 根据内部预订单号取消当前用户的途牛订单，供应商确认后同步本地预订状态。
     *
     * @param context 运行时注入的会话上下文，用户归属以会话身份为准
     * @param bookingId 内部预订单号
     * @return 包含中文处理建议、供应商确认结果和本地同步结果的 JSON 字符串，异常不向模型抛出
     */
    @Tool(name = "cancel_booking_record", description = "用户明确要求取消后调用。使用内部 bookingId 取消当前用户的途牛机票或火车票订单，供应商确认成功后才将本地记录更新为 CANCELLED。仅需 bookingId，不传 userId、API Key 或外部订单号。酒店及其他平台暂不支持。结果不明确时先查询平台状态，禁止自动重试；退款情况需另行核实。")
    public String cancel(AgentRuntimeContext context,
                         @ToolParam(name = "bookingId", description = "需要取消的内部预订单号, 必填", required = true) String bookingId) {
        String cancelledAt = OffsetDateTime.now().toString();
        try {
            // 用户身份仅从会话获取，防止模型传入其他用户的身份操作记录。
            String userId = currentUserId(context);
            var result = cancellationService.cancel(userId, bookingId);
            return JSON.toJSONString(new CancellationToolResult(result.isSuccess(), result.getErrorCode(),
                    result.getMessage(), result.getData(), cancelledAt));
        } catch (Exception exception) {
            // 兜底捕获服务和序列化等异常，避免框架把异常堆栈直接提供给模型。
            logger.error("[TOOL][cancel_booking_record] 取消失败，异常类型={}", exception.getClass().getSimpleName());
            return JSON.toJSONString(new CancellationToolResult(false, "CANCEL_FAILED",
                    "取消结果暂时无法确认，请先查询途牛订单实际状态，未核实前不要重复发起取消。", null, cancelledAt));
        }
    }

    /** 读取会话用户；缺失时返回空值，由工具生成可读提示，不通过抛出异常完成参数校验。 */
    private static String currentUserId(AgentRuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return null;
        }
        return context.getUserId().trim();
    }

    /** 正式预订记录写入结果。 */
    private record ToolResult(boolean success, String errorCode, String message, Object data, String savedAt) {
    }

    /** 预订取消结果；时间表示本次操作时间，失败时不能据此推断取消已经完成。 */
    private record CancellationToolResult(boolean success, String errorCode, String message, Object data,
                                          String cancelledAt) {
    }
}

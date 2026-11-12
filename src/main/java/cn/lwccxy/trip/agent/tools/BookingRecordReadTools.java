package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordQueryForm;
import cn.lwccxy.trip.business.bookingrecord.service.BookingRecordService;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 提供正式业务预订记录查询工具。 */
@Component
public class BookingRecordReadTools {

    private static final Logger logger = LoggerFactory.getLogger(BookingRecordReadTools.class);
    private final BookingRecordService service;

    /** 创建正式预订记录读取工具。 */
    public BookingRecordReadTools(BookingRecordService service) {
        this.service = service;
    }

    /** 按预订单号、差旅单、类型或状态查询当前用户的正式预订记录。 */
    @Tool(name = "query_booking_records", description = "查询当前登录用户的正式预订记录，可按 bookingId、travelOrderId、bizType 和 status 筛选。读取结果来自 booking_record，不能用于替代下单前的幂等预占检查。")
    public String query(AgentRuntimeContext context,
                        @ToolParam(name = "bookingId", description = "内部预订单号，可选", required = false) String bookingId,
                        @ToolParam(name = "travelOrderId", description = "差旅单 ID，可选", required = false) String travelOrderId,
                        @ToolParam(name = "bizType", description = "业务类型，可选：FLIGHT、HOTEL、TRAIN、TICKET、CRUISE、VACATION", required = false) String bizType,
                        @ToolParam(name = "status", description = "预订状态，可选", required = false) String status) {
        String queriedAt = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(context);
            BookingRecordQueryForm form = new BookingRecordQueryForm();
            form.setBookingId(bookingId);
            form.setTravelOrderId(travelOrderId);
            form.setBizType(bizType);
            form.setStatus(status);
            var result = service.list(userId, form);
            return JSON.toJSONString(new ToolResult(true, null, "正式预订记录查询成功。", result.getData(), queriedAt));
        } catch (Exception exception) {
            logger.error("[TOOL][query_booking_records] 查询正式预订记录失败", exception);
            return JSON.toJSONString(new ToolResult(false, "QUERY_FAILED", "预订记录查询失败，请稍后重试。", null, queriedAt));
        }
    }

    /** 校验当前 Agent 会话绑定的用户身份。 */
    private static String requireUserId(AgentRuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，无法查询预订记录。");
        }
        return context.getUserId().trim();
    }

    /** 正式预订记录查询结果。 */
    private record ToolResult(boolean success, String errorCode, String message, Object data, String queriedAt) {
    }
}

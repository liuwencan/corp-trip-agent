package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.service.CityTransitTimeService;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 检测当前用户已有差旅行程与拟申请行程之间的日期和城市衔接冲突。 */
@Component
public class TripScheduleConflictTools {

    private static final Logger logger = LoggerFactory.getLogger(TripScheduleConflictTools.class);
    private static final int SAME_DAY_TIGHT_MINUTES = 8 * 60;
    private static final int SAME_DAY_IMPOSSIBLE_MINUTES = 24 * 60;
    private static final int ADJACENT_DAY_MIN_TRANSIT_MINUTES = 8 * 60;

    private final TripOrderService tripOrderService;
    private final CityTransitTimeService cityTransitTimeService;

    /** 创建行程冲突检测工具。 */
    public TripScheduleConflictTools(TripOrderService tripOrderService,
                                    CityTransitTimeService cityTransitTimeService) {
        this.tripOrderService = tripOrderService;
        this.cityTransitTimeService = cityTransitTimeService;
    }

    /** 检查当前用户拟申请路线与已有有效行程的四类冲突并返回结构化报告。 */
    @Tool(name = "check_trip_schedule_conflicts", description = "创建出差申请前必须调用。传入拟申请出发城市、目的地和日期，检查当前用户草稿、待审批、已审批行程的四类冲突：同路线日期重叠、跨城日期重叠、同日跨城衔接、相邻日跨城路径断裂。返回 JSON：has_conflict、total_conflicts、conflicts（含 type、severity、order_id、order_summary、description、suggestion）、summary。日期格式 YYYY-MM-DD。")
    public String checkTripScheduleConflicts(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "departureCity", description = "必填，拟申请出差的出发城市，最多 128 个字符") String departureCity,
            @ToolParam(name = "destination", description = "必填，拟申请出差的目的地城市，最多 256 个字符") String destination,
            @ToolParam(name = "departureDate", description = "必填，拟申请出差开始日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "returnDate", description = "必填，拟申请出差结束日期，格式 YYYY-MM-DD") String returnDate) {
        try {
            String userId = requireUserId(sessionContext);
            String normalizedDepartureCity = requireCity(departureCity, "departureCity", 128);
            String normalizedDestination = requireCity(destination, "destination", 256);
            LocalDate departure = parseDate(departureDate, "departureDate");
            LocalDate returning = parseDate(returnDate, "returnDate");
            if (departure.isAfter(returning)) {
                return errorReport("INVALID_PARAMETER", "出发日期不能晚于返回日期，请修正申请日期后重新检查。");
            }

            // 查询窗口覆盖候选日期前后一天，确保能发现跨行程交通衔接问题。
            List<TripOrderVO> candidates = tripOrderService.findActiveNearDateRange(
                    userId, departure.toString(), returning.toString());
            List<ConflictItem> conflicts = new ArrayList<>();
            for (TripOrderVO order : candidates) {
                if (!isValidOrderDates(order)) {
                    logger.warn("跳过日期格式异常的已有出差申请，orderId={}", order.getId());
                    continue;
                }
                detectConflict(order, normalizedDepartureCity, normalizedDestination,
                        departure, returning, conflicts);
            }

            // 严重冲突优先呈现，方便模型先向用户解释最关键的风险。
            conflicts.sort(Comparator.comparingInt((ConflictItem item) -> severityRank(item.severity()))
                    .thenComparing(item -> item.orderId() == null ? "" : item.orderId()));
            return JSON.toJSONString(buildReport(conflicts, normalizedDepartureCity,
                    normalizedDestination, departure, returning));
        } catch (BizException exception) {
            return errorReport(exception.getErrorCode(), friendlyBizMessage(exception));
        } catch (IllegalArgumentException exception) {
            return errorReport("INVALID_PARAMETER", safeMessage(exception,
                    "请求参数无效，请检查出发城市、目的地和日期格式。"));
        } catch (Exception exception) {
            logger.error("[TOOL][check_trip_schedule_conflicts] 行程冲突检查失败", exception);
            return errorReport("INTERNAL_ERROR",
                    "行程数据查询失败，当前无法确认是否存在冲突，请稍后重试；检查成功前不要创建申请。");
        }
    }

    /** 针对一条已有申请识别时间重叠、同日衔接或相邻日路径问题。 */
    private void detectConflict(TripOrderVO existing, String candidateDepartureCity, String candidateDestination,
                                LocalDate candidateDeparture, LocalDate candidateReturn,
                                List<ConflictItem> conflicts) {
        LocalDate existingDeparture = LocalDate.parse(existing.getDepartureDate());
        LocalDate existingReturn = LocalDate.parse(existing.getReturnDate());

        // 先判断同日交接，以免把“当天结束后当天出发”误归类为普通日期重叠。
        if (existingReturn.equals(candidateDeparture)
                && !(existingDeparture.equals(candidateReturn) && existingReturn.equals(candidateDeparture))) {
            addSameDayTransitConflict(existing, existing.getDestination(), candidateDepartureCity, conflicts);
            return;
        }
        if (existingDeparture.equals(candidateReturn)
                && !(existingDeparture.equals(candidateReturn) && existingReturn.equals(candidateDeparture))) {
            addSameDayTransitConflict(existing, candidateDestination, existing.getDepartureCity(), conflicts);
            return;
        }

        // 日期首尾均视为行程日，区间相交就按同路线重复或跨城冲突处理。
        if (!existingReturn.isBefore(candidateDeparture) && !existingDeparture.isAfter(candidateReturn)) {
            addDateOverlapConflict(existing, candidateDepartureCity, candidateDestination,
                    candidateDeparture, candidateReturn, conflicts);
            return;
        }

        if (existingReturn.plusDays(1).equals(candidateDeparture)) {
            addAdjacentDayConflict(existing, "departure", existingReturn, existing.getDestination(),
                    candidateDeparture, candidateDepartureCity, conflicts);
            return;
        }
        if (candidateReturn.plusDays(1).equals(existingDeparture)) {
            addAdjacentDayConflict(existing, "return", candidateReturn, candidateDestination,
                    existingDeparture, existing.getDepartureCity(), conflicts);
        }
    }

    /** 根据路线是否相同区分重复申请提示和跨城市同时出行冲突。 */
    private void addDateOverlapConflict(TripOrderVO existing,
                                       String candidateDepartureCity, String candidateDestination,
                                       LocalDate candidateDeparture, LocalDate candidateReturn,
                                       List<ConflictItem> conflicts) {
        conflicts.add(createDateOverlapConflict(existing, candidateDepartureCity, candidateDestination,
                candidateDeparture, candidateReturn));
    }

    /** 为创建入口的二次检查生成与冲突检测工具相同的日期重叠明细。 */
    public static ConflictItem createDateOverlapConflict(TripOrderVO existing,
                                                         String candidateDepartureCity,
                                                         String candidateDestination,
                                                         LocalDate candidateDeparture,
                                                         LocalDate candidateReturn) {
        LocalDate existingDeparture = LocalDate.parse(existing.getDepartureDate());
        LocalDate existingReturn = LocalDate.parse(existing.getReturnDate());
        LocalDate overlapStart = existingDeparture.isAfter(candidateDeparture)
                ? existingDeparture : candidateDeparture;
        LocalDate overlapEnd = existingReturn.isBefore(candidateReturn) ? existingReturn : candidateReturn;
        String summary = String.format("%s -> %s，%s ~ %s（%s）", existing.getDepartureCity(),
                existing.getDestination(), existing.getDepartureDate(), existing.getReturnDate(), existing.getStatus());
        if (sameCity(existing.getDepartureCity(), candidateDepartureCity)
                && sameCity(existing.getDestination(), candidateDestination)) {
            return new ConflictItem("TIME_OVERLAP_SAME_CITY", "LOW", existing.getId(), summary,
                    String.format("已有申请与本次申请路线相同，日期在 %s 至 %s 重叠，可能是重复提交。",
                            overlapStart, overlapEnd),
                    "建议核对是否重复申请；如确为不同事项，可在用户知情后继续提交。");
        }

        String description = String.format("已有行程 %s 至 %s（%s -> %s）与本次行程 %s 至 %s（%s -> %s）"
                        + "在 %s 至 %s 日期重叠，路线不同，无法同时身处两地。",
                existingDeparture, existingReturn, existing.getDepartureCity(), existing.getDestination(),
                candidateDeparture, candidateReturn, candidateDepartureCity, candidateDestination,
                overlapStart, overlapEnd);
        if (sameCity(existing.getDepartureCity(), candidateDestination)
                && sameCity(existing.getDestination(), candidateDepartureCity)) {
            description += "两条路线方向相反，请确认是否为同一趟往返行程被重复拆单。";
        }
        return new ConflictItem("TIME_OVERLAP_DIFF_CITY", "HIGH", existing.getId(), summary,
                description, "调整本次申请的出发或返回日期以避开重叠区间，或先修改、取消已有申请。");
    }

    /** 判断同一天从已有行程结束城市赶往下一段行程出发城市是否可行。 */
    private void addSameDayTransitConflict(TripOrderVO existing, String endCity, String startCity,
                                          List<ConflictItem> conflicts) {
        int minutes = cityTransitTimeService.estimateMinutes(endCity, startCity);
        if (minutes <= 0) {
            return;
        }
        String description;
        String severity;
        String suggestion;
        if (minutes > SAME_DAY_IMPOSSIBLE_MINUTES) {
            severity = "HIGH";
            description = String.format("同一天需要从 %s 前往 %s，估算最短衔接时间约 %d 小时，超过一天可行范围。",
                    endCity, startCity, minutes / 60);
            suggestion = "建议至少将后一段行程延后一天，或调整出发城市。";
        } else if (minutes > SAME_DAY_TIGHT_MINUTES) {
            severity = "MEDIUM";
            description = String.format("同一天需要从 %s 前往 %s，估算最短衔接时间约 %d 小时，衔接非常紧张。",
                    endCity, startCity, minutes / 60);
            suggestion = "建议改为次日出发，或调整路线，为城际交通预留缓冲。";
        } else {
            severity = "MEDIUM";
            description = String.format("同一天需要从 %s 前往 %s，估算衔接时间约 %d 小时，存在赶车风险。",
                    endCity, startCity, minutes / 60);
            suggestion = "建议选择较早的交通班次，并预留交通和进站缓冲时间。";
        }
        conflicts.add(new ConflictItem("TRANSIT_TOO_TIGHT", severity, existing.getId(), orderSummary(existing),
                description, suggestion));
    }

    /** 判断相邻两天之间的跨城衔接是否超过一天可用的估算时间。 */
    private void addAdjacentDayConflict(TripOrderVO existing, String side, LocalDate endDate,
                                        String endCity, LocalDate nextDate, String startCity,
                                        List<ConflictItem> conflicts) {
        if (sameCity(endCity, startCity)) {
            return;
        }
        int minutes = cityTransitTimeService.estimateMinutes(endCity, startCity);
        if (minutes <= ADJACENT_DAY_MIN_TRANSIT_MINUTES) {
            return;
        }
        String relation = "departure".equals(side)
                ? String.format("已有行程于 %s 在 %s 结束，拟申请次日（%s）从 %s 出发", endDate, endCity, nextDate, startCity)
                : String.format("拟申请于 %s 在 %s 结束，已有行程次日（%s）从 %s 出发", endDate, endCity, nextDate, startCity);
        String description = String.format("%s；城市间估算交通衔接时间约 %d 小时，一天内衔接偏紧，存在赶不上交通班次的风险。",
                relation, minutes / 60);
        conflicts.add(new ConflictItem("DISCONNECTED_ROUTE", "MEDIUM", existing.getId(), orderSummary(existing),
                description, "建议在两段行程之间增加缓冲时间，或调整其中一段的出发城市、日期。"));
    }

    /** 汇总冲突明细并生成模型可直接解释的严重级别计数。 */
    private ConflictReport buildReport(List<ConflictItem> conflicts, String departureCity, String destination,
                                       LocalDate departure, LocalDate returning) {
        long high = countSeverity(conflicts, "HIGH");
        long medium = countSeverity(conflicts, "MEDIUM");
        long low = countSeverity(conflicts, "LOW");
        String summary = conflicts.isEmpty()
                ? String.format("已检查 %s -> %s（%s 至 %s），未发现冲突。",
                departureCity, destination, departure, returning)
                : String.format("命中 %d 条冲突（HIGH=%d, MEDIUM=%d, LOW=%d），请逐条阅读冲突原因和建议。",
                conflicts.size(), high, medium, low);
        return new ConflictReport(!conflicts.isEmpty(), conflicts.size(), conflicts, summary);
    }

    /** 统计指定严重级别的冲突数量。 */
    private long countSeverity(List<ConflictItem> conflicts, String severity) {
        return conflicts.stream().filter(item -> severity.equals(item.severity())).count();
    }

    /** 将失败原因转换为与成功报告兼容的 JSON，并明确提示不得视为无冲突。 */
    private String errorReport(String errorCode, String message) {
        return JSON.toJSONString(new ConflictErrorReport(false, errorCode, false, 0, List.of(), message));
    }

    /** 判断已有申请的日期字段是否齐全且格式正确。 */
    private boolean isValidOrderDates(TripOrderVO order) {
        try {
            return order.getDepartureDate() != null && order.getReturnDate() != null
                    && !LocalDate.parse(order.getDepartureDate()).isAfter(LocalDate.parse(order.getReturnDate()));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /** 生成已有申请的路线、日期和状态摘要。 */
    private String orderSummary(TripOrderVO order) {
        return String.format("%s -> %s，%s ~ %s（%s）", order.getDepartureCity(), order.getDestination(),
                order.getDepartureDate(), order.getReturnDate(), order.getStatus());
    }

    /** 比较城市名称并兼容“上海”与“上海市”等常见写法。 */
    private static boolean sameCity(String first, String second) {
        String normalizedFirst = normalizeCity(first);
        String normalizedSecond = normalizeCity(second);
        return !normalizedFirst.isEmpty() && normalizedFirst.equals(normalizedSecond);
    }

    /** 归一化城市名称，忽略空白、大小写和末尾“市”字。 */
    private static String normalizeCity(String city) {
        if (city == null) {
            return "";
        }
        String normalized = city.trim();
        if (normalized.endsWith("市") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** 按 HIGH、MEDIUM、LOW 返回排序权重。 */
    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    /** 校验城市名称必填并限制长度。 */
    private static String requireCity(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空，请提供本次出差城市信息。");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 过长，请缩短城市名称后重试。");
        }
        return normalized;
    }

    /** 解析工具传入的日期，并将格式错误转换为模型可解释的参数提示。 */
    private static LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空，请使用 YYYY-MM-DD 格式提供日期。");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + " 格式无效：请使用 YYYY-MM-DD，例如 2026-11-06。");
        }
    }

    /** 校验会话中由系统注入的当前登录用户。 */
    private static String requireUserId(AgentRuntimeContext sessionContext) {
        if (sessionContext == null || sessionContext.getUserId() == null || sessionContext.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，无法检查行程冲突。请重新登录后再试。");
        }
        return sessionContext.getUserId().trim();
    }

    /** 将领域参数异常转成对模型有指导意义的用户提示。 */
    private static String friendlyBizMessage(BizException exception) {
        if (TripErrorCode.INVALID_PARAMETER.getErrorCode().equals(exception.getErrorCode())) {
            return "拟申请的日期范围无效，请确认出发日期不晚于返回日期，并使用 YYYY-MM-DD 格式。";
        }
        return "行程冲突检查未能完成，暂时无法确认已有行程；检查成功前不要创建申请。";
    }

    /** 提取异常信息；异常无可读消息时返回安全的中文兜底提示。 */
    private static String safeMessage(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback : exception.getMessage();
    }

    /** 冲突检测报告，字段名保持与行程管理工具契约一致。 */
    public record ConflictReport(@JSONField(name = "has_conflict") boolean hasConflict,
                                 @JSONField(name = "total_conflicts") int totalConflicts,
                                 List<ConflictItem> conflicts,
                                 String summary) {
    }

    /** 冲突检查失败时的报告，明确标记结果不可作为无冲突依据。 */
    public record ConflictErrorReport(boolean success,
                                      @JSONField(name = "error_code") String errorCode,
                                      @JSONField(name = "has_conflict") boolean hasConflict,
                                      @JSONField(name = "total_conflicts") int totalConflicts,
                                      List<ConflictItem> conflicts,
                                      String summary) {
    }

    /** 单条冲突的类型、严重级别、原因及建议。 */
    public record ConflictItem(String type,
                               String severity,
                               @JSONField(name = "order_id") String orderId,
                               @JSONField(name = "order_summary") String orderSummary,
                               String description,
                               String suggestion) {
    }
}

package cn.lwccxy.trip.business.agenttrace.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Agent 调用链路分页筛选参数。 */
@Getter
@Setter
public class AgentTraceQueryForm {

    /** 页码，从 1 开始。 */
    @Min(1)
    private Long pageNo = 1L;
    /** 每页条数，最大 100。 */
    @Min(1)
    @Max(100)
    private Long pageSize = 20L;
    /** 按链路 ID 精确筛选。 */
    @Size(max = 64)
    private String traceId;
    /** 按会话 ID 筛选。 */
    private Long sessionId;
    /** 按用户 ID 筛选。 */
    private Long userId;
    /** 按 Span 类型筛选。 */
    @Pattern(regexp = "AGENT|MODEL|TOOL|SUMMARY")
    private String spanType;
    /** 按调用状态筛选。 */
    @Pattern(regexp = "RUNNING|SUCCESS|FAILED|SUSPENDED")
    private String status;
    /** 按开始时间下限筛选。 */
    private LocalDateTime startFrom;
    /** 按开始时间上限筛选。 */
    private LocalDateTime startTo;
}

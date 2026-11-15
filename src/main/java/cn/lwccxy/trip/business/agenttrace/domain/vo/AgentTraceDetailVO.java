package cn.lwccxy.trip.business.agenttrace.domain.vo;

import lombok.Data;

import java.util.List;

/** Agent 单次对话链路详情及汇总指标。 */
@Data
public class AgentTraceDetailVO {

    /** 本次对话的链路 ID。 */
    private String traceId;
    /** 整条链路的墙钟耗时，单位毫秒。 */
    private Long totalDurationMs;
    /** 本次对话所有模型调用的输入 Token 总数。 */
    private Long totalInputTokens;
    /** 本次对话所有模型调用的输出 Token 总数。 */
    private Long totalOutputTokens;
    /** 本次对话包含的全部 Span。 */
    private List<AgentTraceVO> spans;
}

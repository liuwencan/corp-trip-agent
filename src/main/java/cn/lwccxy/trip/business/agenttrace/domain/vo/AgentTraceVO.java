package cn.lwccxy.trip.business.agenttrace.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** Agent 调用链路 Span 接口视图。 */
@Data
public class AgentTraceVO {

    private Long id;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private Long sessionId;
    private Long userId;
    private String agentId;
    private String agentName;
    private String spanType;
    private String operationName;
    private String status;
    private Integer iteration;
    private String modelName;
    private String toolName;
    private String toolCallId;
    private Integer inputMessageCount;
    private Long inputChars;
    private Long outputChars;
    private String inputMessages;
    private String outputMessage;
    private String toolResult;
    private Long inputTokens;
    private Long outputTokens;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorSummary;
    private String attributes;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

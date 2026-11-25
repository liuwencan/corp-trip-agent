package cn.lwccxy.trip.business.agenttrace.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 新增或更新 Agent 调用链路 Span 的请求参数。 */
@Getter
@Setter
public class AgentTraceSaveForm {

    /** 一次请求的链路 ID。 */
    @NotBlank
    @Size(max = 64)
    private String traceId;
    /** 单次调用的 Span ID。 */
    @NotBlank
    @Size(max = 64)
    private String spanId;
    /** 父 Span ID。 */
    @Size(max = 64)
    private String parentSpanId;
    /** 会话 ID。 */
    @Positive
    private Long sessionId;
    /** 用户 ID。 */
    @Positive
    private Long userId;
    /** Agent 实例 ID。 */
    @Size(max = 64)
    private String agentId;
    /** Agent 名称。 */
    @Size(max = 128)
    private String agentName;
    /** 调用类型。 */
    @NotBlank
    @Pattern(regexp = "AGENT|MODEL|TOOL|SUMMARY")
    private String spanType;
    /** 操作名称。 */
    @Size(max = 128)
    private String operationName;
    /** 调用状态。 */
    @NotBlank
    @Pattern(regexp = "RUNNING|SUCCESS|FAILED|SUSPENDED")
    private String status = "RUNNING";
    /** 模型推理轮次。 */
    @Min(1)
    private Integer iteration;
    /** 模型名称。 */
    @Size(max = 128)
    private String modelName;
    /** 工具名称。 */
    @Size(max = 128)
    private String toolName;
    /** 工具调用 ID。 */
    @Size(max = 128)
    private String toolCallId;
    /** 本次工具调用的实际入参 JSON。 */
    @Size(max = 1000000)
    private String toolInput;
    /** 输入消息数。 */
    @PositiveOrZero
    private Integer inputMessageCount;
    /** 输入字符数。 */
    @PositiveOrZero
    private Long inputChars;
    /** 输出字符数。 */
    @PositiveOrZero
    private Long outputChars;
    /** 本轮推理输入消息 JSON。 */
    @Size(max = 1000000)
    private String inputMessages;
    /** 本轮推理或总结输出消息 JSON。 */
    @Size(max = 1000000)
    private String outputMessage;
    /** 单个工具调用结果 JSON。 */
    @Size(max = 1000000)
    private String toolResult;
    /** 输入 Token 数。 */
    @PositiveOrZero
    private Long inputTokens;
    /** 输出 Token 数。 */
    @PositiveOrZero
    private Long outputTokens;
    /** 调用开始时间。 */
    @NotNull
    private LocalDateTime startTime;
    /** 调用结束时间。 */
    private LocalDateTime endTime;
    /** 调用耗时，单位毫秒。 */
    @PositiveOrZero
    private Long durationMs;
    /** 脱敏后的异常摘要。 */
    @Size(max = 1000)
    private String errorSummary;
    /** 非敏感扩展信息 JSON。 */
    @Size(max = 1000000)
    private String attributes;
}

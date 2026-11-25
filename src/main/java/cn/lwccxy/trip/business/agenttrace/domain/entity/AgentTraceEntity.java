package cn.lwccxy.trip.business.agenttrace.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent 调用链路 Span 数据库实体。 */
@Data
@TableName("t_agent_trace")
public class AgentTraceEntity {

    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 一次请求的链路 ID。 */
    private String traceId;
    /** 单次调用的 Span ID。 */
    private String spanId;
    /** 父 Span ID。 */
    private String parentSpanId;
    /** 会话 ID。 */
    private Long sessionId;
    /** 用户 ID。 */
    private Long userId;
    /** Agent 实例 ID。 */
    private String agentId;
    /** Agent 名称。 */
    private String agentName;
    /** 调用类型。 */
    private String spanType;
    /** 操作名称。 */
    private String operationName;
    /** 调用状态。 */
    private String status;
    /** 模型推理轮次。 */
    private Integer iteration;
    /** 模型名称。 */
    private String modelName;
    /** 工具名称。 */
    private String toolName;
    /** 工具调用 ID。 */
    private String toolCallId;
    /** 本次工具调用的实际入参 JSON。 */
    private String toolInput;
    /** 输入消息数。 */
    private Integer inputMessageCount;
    /** 输入字符数。 */
    private Long inputChars;
    /** 输出字符数。 */
    private Long outputChars;
    /** 本轮推理的输入消息 JSON。 */
    private String inputMessages;
    /** 本轮推理或总结输出的消息 JSON。 */
    private String outputMessage;
    /** 单个工具调用的结果 JSON。 */
    private String toolResult;
    /** 输入 Token 数。 */
    private Long inputTokens;
    /** 输出 Token 数。 */
    private Long outputTokens;
    /** 调用开始时间。 */
    private LocalDateTime startTime;
    /** 调用结束时间。 */
    private LocalDateTime endTime;
    /** 调用耗时，单位毫秒。 */
    private Long durationMs;
    /** 脱敏后的异常摘要。 */
    private String errorSummary;
    /** 非敏感扩展信息 JSON。 */
    private String attributes;
    /** 记录创建时间。 */
    private LocalDateTime createTime;
    /** 记录更新时间。 */
    private LocalDateTime updateTime;
}

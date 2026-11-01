package cn.lwccxy.trip.agent.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/** 智能体时间线的统一事件数据；空字段也保留，便于前端按固定结构解析。 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AgentProgressVO {

    /** 本次推送的唯一标识，不作为工具调用的关联键。 */
    private String eventId;

    /** 事件类型，与 SSE 的事件名称一致。 */
    private String eventType;

    /** 智能体实例标识，用于区分不同智能体的并行调用。 */
    private String agentId;

    /** 智能体展示名称，未配置中文名称时保留原始名称。 */
    private String agentName;

    /** 工具或当前动作的展示描述，字段拼写固定为 tooldesc。 */
    private String tooldesc;

    /** 事件产生时的 Unix 毫秒时间戳。 */
    private long timestamp;

    /** 工具开始和结果事件共享的调用标识；文本事件为空。 */
    private String toolCallId;

    /** 工具原始名称；文本事件为空。 */
    private String toolName;

    /** 工具原始入参对象，开始和结果事件均携带；文本事件为空。 */
    private Map<String, Object> input;

    /** 工具结果的文本或智能体本次输出的增量文本；工具开始时为空。 */
    private String output;

    /** 智能体消息标识，同一条消息的文本增量按此字段归并。 */
    private String messageId;

    /** 阶段状态：running、completed、suspended；completed 不代表业务执行成功。 */
    private String status;
}

package cn.lwccxy.trip.agent.enums;

/** 意图识别所达到的层级。 */
public enum RecognitionLevel {
    /** 多结构启发，只提供线索。 */
    L0,
    /** 正则规则识别。 */
    L1,
    /** 向量库语义检索。 */
    L2,
    /** 大模型识别。 */
    L3,
    /** 当前层级尚未形成完整识别结果。 */
    NONE
}

package cn.lwccxy.trip.business.eval.intent.domain.vo;

import lombok.Data;

import java.util.List;

/** 意图识别评测结果及分阶段耗时。 */
@Data
public class IntentEvaluationVO {

    /** 是否识别出多个相互独立的业务意图。 */
    private boolean multiIntent;

    /** 本轮识别涉及的全部意图及各自置信分。 */
    private List<IntentConfidence> intents;

    /** 有效的问题改写结果；没有发生改写时为空字符串。 */
    private String rewrittenQuestion;

    /** 最终形成识别结果的层级。 */
    private String hitLevel;

    /** 各识别阶段的墙钟耗时，单位为毫秒。 */
    private StageTimings timings;

    /** 单个识别意图的置信信息。 */
    @Data
    public static class IntentConfidence {
        /** 意图英文编码。 */
        private String intent;
        /** 对应的路由目标智能体。 */
        private String targetAgent;
        /** 置信分；L1 规则命中为 1.0，原识别器未暴露 L2/L3 分数时为空。 */
        private Double confidence;
    }

    /** 意图识别各层累计耗时。 */
    @Data
    public static class StageTimings {
        /** 原问题及改写问题的 L1 规则识别累计耗时。 */
        private long l1Ms;
        /** 原问题及改写问题的 L2 语义检索累计耗时。 */
        private long l2Ms;
        /** 原问题第一次经过 L1 规则识别的耗时。 */
        private long originalL1Ms;
        /** 原问题第一次经过 L2 语义检索的耗时。 */
        private long originalL2Ms;
        /** 问题改写后再次经过 L1 规则识别的耗时；未发生改写或未执行时为 0。 */
        private long rewrittenL1Ms;
        /** 问题改写后再次经过 L2 语义检索的耗时；未发生改写或未执行时为 0。 */
        private long rewrittenL2Ms;
        /** L3 大模型兜底识别耗时；未触发时为 0。 */
        private long l3Ms;
        /** 查询改写模型耗时；未触发时为 0。 */
        private long rewriteMs;
        /** 整个意图识别调用的总墙钟耗时。 */
        private long totalMs;
    }
}

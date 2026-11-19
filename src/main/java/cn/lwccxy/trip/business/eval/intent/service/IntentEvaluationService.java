package cn.lwccxy.trip.business.eval.intent.service;

import cn.lwccxy.trip.agent.intent.IntentRecognizer;
import cn.lwccxy.trip.business.eval.intent.domain.form.IntentEvaluationForm;
import cn.lwccxy.trip.business.eval.intent.domain.vo.IntentEvaluationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** 为离线评测提供与聊天链路相同的分层意图识别能力。 */
@Service
@RequiredArgsConstructor
public class IntentEvaluationService {

    private final IntentRecognizer intentRecognizer;

    /**
     * 对单条原始问题运行完整意图识别，并汇总阶段耗时和分类置信分。
     *
     * @param form 包含原始用户问题的评测请求
     * @return 意图、路由、改写、置信度、命中层级和阶段耗时
     */
    public IntentEvaluationVO evaluate(IntentEvaluationForm form) {
        long totalStartNanos = System.nanoTime();
        StageTimer timer = new StageTimer();
        IntentRecognizer.IntentRecognitionResult result = intentRecognizer.recognize(
                form.getQuestion(), java.util.List.of(), timer::record);

        IntentEvaluationVO response = new IntentEvaluationVO();
        response.setMultiIntent(result.multiIntent());
        response.setRewrittenQuestion(result.rewrittenQuestion() == null ? "" : result.rewrittenQuestion());
        response.setHitLevel(result.level().name());
        response.setIntents(result.intents().stream().map(recognized -> {
            IntentEvaluationVO.IntentConfidence confidence = new IntentEvaluationVO.IntentConfidence();
            confidence.setIntent(recognized.originalIntent().getTargetIntent());
            confidence.setTargetAgent(recognized.targetAgent());
            // 评测接口不修改线上识别器的结果契约；只有 L1 规则命中具备确定性置信值，其余层暂不虚构概率。
            confidence.setConfidence(result.level() == cn.lwccxy.trip.agent.enums.RecognitionLevel.L1
                    ? 1.0D : null);
            return confidence;
        }).toList());

        // 总耗时用端到端墙钟时间测量，包含识别器内部未单独暴露的结构分析和结果组装开销。
        IntentEvaluationVO.StageTimings timings = timer.toTimings();
        timings.setTotalMs(elapsedMs(totalStartNanos));
        response.setTimings(timings);
        return response;
    }

    /**
     * 计算单调时钟区间的毫秒数，避免系统时间校准影响耗时。
     *
     * @param startNanos 单调时钟开始值
     * @return 已经过的毫秒数
     */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 聚合识别器进度回调中的阶段耗时，兼容原问题和改写问题的重复识别轮次。 */
    private static class StageTimer {
        private final Map<String, Long> starts = new HashMap<>();
        private final Map<String, Long> elapsed = new HashMap<>();

        /** 根据阶段开始和完成事件累计本阶段耗时，并按原问题或改写问题区分 L1/L2 轮次。 */
        private void record(IntentRecognizer.IntentRecognitionProgress progress) {
            String stageKey = switch (progress.stage()) {
                case "rule_matching" -> "l1";
                case "semantic_retrieval" -> "l2";
                case "llm_fallback" -> "l3";
                case "question_rewrite" -> "rewrite";
                default -> null;
            };
            if (stageKey == null) {
                return;
            }
            // L1/L2 进度携带 original 或 rewritten 轮次编码，L3 和改写阶段使用自身累计键。
            String key = switch (stageKey) {
                case "l1", "l2" -> stageKey + "-" + normalizePass(progress.passName());
                default -> stageKey;
            };
            if ("started".equals(progress.status())) {
                starts.put(key, System.nanoTime());
            } else if ("completed".equals(progress.status())) {
                Long start = starts.remove(key);
                if (start != null) {
                    long durationNanos = System.nanoTime() - start;
                    elapsed.merge(key, durationNanos, Long::sum);
                    if ("l1".equals(stageKey) || "l2".equals(stageKey)) {
                        elapsed.merge(stageKey, durationNanos, Long::sum);
                    }
                }
            }
        }

        /** 将纳秒累计值转换为接口使用的累计和分轮次阶段指标。 */
        private IntentEvaluationVO.StageTimings toTimings() {
            IntentEvaluationVO.StageTimings timings = new IntentEvaluationVO.StageTimings();
            timings.setL1Ms(elapsed.getOrDefault("l1", 0L) / 1_000_000);
            timings.setL2Ms(elapsed.getOrDefault("l2", 0L) / 1_000_000);
            timings.setOriginalL1Ms(elapsed.getOrDefault("l1-original", 0L) / 1_000_000);
            timings.setOriginalL2Ms(elapsed.getOrDefault("l2-original", 0L) / 1_000_000);
            timings.setRewrittenL1Ms(elapsed.getOrDefault("l1-rewritten", 0L) / 1_000_000);
            timings.setRewrittenL2Ms(elapsed.getOrDefault("l2-rewritten", 0L) / 1_000_000);
            timings.setL3Ms(elapsed.getOrDefault("l3", 0L) / 1_000_000);
            timings.setRewriteMs(elapsed.getOrDefault("rewrite", 0L) / 1_000_000);
            return timings;
        }

        /** 归一化识别轮次编码，避免异常回调把耗时写入无法识别的分组。 */
        private String normalizePass(String passName) {
            return "rewritten".equals(passName) ? "rewritten" : "original";
        }
    }
}

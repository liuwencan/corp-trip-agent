package cn.lwccxy.trip.agent.utils;

import cn.lwccxy.trip.agent.enums.ModelPricingEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 按模型普通输入、输出和缓存读取 Token 单价估算单次调用费用。
 * 价格以人民币元计，适用于文本 Token 按量计费，不含多模态专属价格。
 */
public final class ModelTokenBillingUtil {

    private static final int RESULT_SCALE = 8;

    /**
     * 禁止实例化静态计费工具。
     */
    private ModelTokenBillingUtil() {
    }

    /**
     * 按普通输入和输出单价计算一次模型调用费用。
     *
     * @param inputTokens 输入 Token 数量，不得小于零
     * @param outputTokens 输出 Token 数量，不得小于零
     * @param model 本次调用使用的模型价格枚举
     * @return 人民币元，保留八位小数
     * @throws IllegalArgumentException Token 数量为负数时抛出
     * @throws NullPointerException 模型枚举为空时抛出
     */
    public static BigDecimal calculateCost(long inputTokens, long outputTokens, ModelPricingEnum model) {
        // 通用用量未提供缓存明细时，全部输入按普通价格估算，不推测缓存命中数量。
        return calculateCost(inputTokens, outputTokens, 0, model);
    }

    /**
     * 按缓存明细计算费用，缓存读取数量是总输入数量的子集，不重复计费。
     *
     * @param inputTokens 总输入 Token 数量，包含缓存读取数量，不得小于零
     * @param outputTokens 输出 Token 数量，不得小于零
     * @param cacheReadTokens 缓存读取 Token 数量，介于零和总输入数量之间
     * @param model 本次调用使用的模型价格枚举
     * @return 人民币元，保留八位小数
     * @throws IllegalArgumentException Token 数量为负数或缓存读取数量超过总输入数量时抛出
     * @throws NullPointerException 模型枚举为空时抛出
     */
    public static BigDecimal calculateCost(long inputTokens, long outputTokens,
                                           long cacheReadTokens, ModelPricingEnum model) {
        Objects.requireNonNull(model, "model 不能为空");
        // 缓存读取属于输入的一部分，先校验数量关系，避免产生负费用或重复收费。
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0) {
            throw new IllegalArgumentException("Token 数量不能为负数");
        }
        if (cacheReadTokens > inputTokens) {
            throw new IllegalArgumentException("缓存读取 Token 数量不能超过总输入 Token 数量");
        }

        // 从总输入中扣除缓存命中部分，三项分别乘以单价后统一换算为人民币元。
        BigDecimal inputCost = model.getInputPricePerMillion()
                .multiply(BigDecimal.valueOf(inputTokens - cacheReadTokens));
        BigDecimal outputCost = model.getOutputPricePerMillion()
                .multiply(BigDecimal.valueOf(outputTokens));
        BigDecimal cacheReadCost = model.getCacheReadPricePerMillion()
                .multiply(BigDecimal.valueOf(cacheReadTokens));
        return inputCost.add(outputCost).add(cacheReadCost)
                .divide(ModelPricingEnum.getTokensPerMillion(), RESULT_SCALE, RoundingMode.HALF_UP);
    }
}

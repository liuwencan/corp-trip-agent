package cn.lwccxy.trip.agent.utils;

import cn.lwccxy.trip.agent.enums.ModelPricingEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 按模型标准输入、输出 Token 单价估算单次调用费用。
 * 价格以人民币元计，适用于文本 Token 标准按量计费，不含缓存优惠及多模态专属价格。
 */
public final class ModelTokenBillingUtil {

    private static final int RESULT_SCALE = 8;

    /**
     * 禁止实例化静态计费工具。
     */
    private ModelTokenBillingUtil() {
    }

    /**
     * 计算一次模型调用的标准 Token 费用。
     *
     * @param inputTokens 输入 Token 数量，不得小于零
     * @param outputTokens 输出 Token 数量，不得小于零
     * @param model 本次调用使用的模型价格枚举
     * @return 人民币元，保留八位小数
     * @throws IllegalArgumentException Token 数量为负数时抛出
     * @throws NullPointerException 模型枚举为空时抛出
     */
    public static BigDecimal calculateCost(long inputTokens, long outputTokens, ModelPricingEnum model) {
        Objects.requireNonNull(model, "model 不能为空");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token 数量不能为负数");
        }

        // 输入和输出分别按百万 Token 单价计费，最后再汇总并统一保留精度。
        BigDecimal inputCost = model.getInputPricePerMillion()
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(ModelPricingEnum.getTokensPerMillion(), RESULT_SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = model.getOutputPricePerMillion()
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(ModelPricingEnum.getTokensPerMillion(), RESULT_SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}

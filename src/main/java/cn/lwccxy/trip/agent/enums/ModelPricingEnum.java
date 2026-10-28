package cn.lwccxy.trip.agent.enums;

import java.math.BigDecimal;

/**
 * 项目使用的生成模型及其标准文本 Token 单价。
 * 单价以人民币元/百万 Token 计，依据千问官方模型页维护：https://www.qianwenai.com/models/qwen3.8-flash。
 */
public enum ModelPricingEnum {

    /** 当前项目对话和标题生成使用的千问 3.8 Flash 模型。 */
    QWEN_3_8_FLASH("qwen3.8-flash", new BigDecimal("0.8"), new BigDecimal("2.7"), "千问 3.8 Flash");

    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    private final String modelName;
    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;
    private final String description;

    /**
     * 创建模型单价记录。
     *
     * @param modelName 服务端模型名称
     * @param inputPricePerMillion 每百万输入 Token 的人民币价格
     * @param outputPricePerMillion 每百万输出 Token 的人民币价格
     * @param description 模型中文说明
     */
    ModelPricingEnum(String modelName, BigDecimal inputPricePerMillion,
                    BigDecimal outputPricePerMillion, String description) {
        this.modelName = modelName;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
        this.description = description;
    }

    /**
     * 获取服务端使用的模型名称。
     *
     * @return 模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取每百万输入 Token 的人民币价格。
     *
     * @return 输入单价，单位为元/百万 Token
     */
    public BigDecimal getInputPricePerMillion() {
        return inputPricePerMillion;
    }

    /**
     * 获取每百万输出 Token 的人民币价格。
     *
     * @return 输出单价，单位为元/百万 Token
     */
    public BigDecimal getOutputPricePerMillion() {
        return outputPricePerMillion;
    }

    /**
     * 获取模型中文说明。
     *
     * @return 模型说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取用于 Token 单价换算的百万 Token 数量。
     *
     * @return 一百万 Token
     */
    public static BigDecimal getTokensPerMillion() {
        return TOKENS_PER_MILLION;
    }
}

package cn.lwccxy.trip.agent.enums;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * 项目使用的生成模型及其文本 Token 单价。
 * 单价以人民币元/百万 Token 计，依据模型服务商价格维护。
 */
public enum ModelPricingEnum {

    /** 当前项目对话和标题生成使用的千问 3.8 Flash 模型。 */
    QWEN_3_8_FLASH("qwen3.8-flash", new BigDecimal("0.8"), new BigDecimal("2.7"), "千问 3.8 Flash"),
    /** 当前项目配置的千问 3.8 Max 模型，按普通输入和输出 Token 计价。 */
    QWEN_3_8_MAX_0902("qwen3.8-max-0902", new BigDecimal("12"), new BigDecimal("36"),
            "千问 3.8 Max 0902"),
    /** 灵模型新一代长流程版，采用当前优惠价，缓存读取单独计费。 */
    LING_3_1_FLASH("Ling-3.1-flash", new BigDecimal("0.50"), new BigDecimal("1.50"),
            new BigDecimal("0.10"), "灵 3.1 Flash 新一代长流程版");

    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    private final String modelName;
    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;
    private final BigDecimal cacheReadPricePerMillion;
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
        // 未维护缓存优惠价的已有模型继续按普通输入价格估算，保持原计费口径。
        this(modelName, inputPricePerMillion, outputPricePerMillion, inputPricePerMillion, description);
    }

    /**
     * 创建包含缓存读取单价的模型价格记录。
     *
     * @param modelName 服务端模型名称
     * @param inputPricePerMillion 每百万未命中缓存的输入 Token 价格
     * @param outputPricePerMillion 每百万输出 Token 价格
     * @param cacheReadPricePerMillion 每百万缓存读取 Token 价格
     * @param description 模型中文说明
     */
    ModelPricingEnum(String modelName, BigDecimal inputPricePerMillion,
                    BigDecimal outputPricePerMillion, BigDecimal cacheReadPricePerMillion,
                    String description) {
        this.modelName = modelName;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
        this.cacheReadPricePerMillion = cacheReadPricePerMillion;
        this.description = description;
    }

    /**
     * 按服务端模型名称精确查找价格，未知型号不套用其他模型价格。
     *
     * @param modelName 实际调用的模型名称
     * @return 已维护的模型价格，未知名称返回空
     */
    public static Optional<ModelPricingEnum> findByModelName(String modelName) {
        // 保持服务端名称的精确匹配，避免不同版本之间误用单价。
        return Arrays.stream(values())
                .filter(pricing -> pricing.modelName.equals(modelName))
                .findFirst();
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
     * 获取缓存读取单价；未维护缓存优惠价的模型按普通输入价格估算。
     *
     * @return 缓存读取单价，单位为元/百万 Token
     */
    public BigDecimal getCacheReadPricePerMillion() {
        return cacheReadPricePerMillion;
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

package cn.lwccxy.trip.agent.config;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * TODO 后续可以根据任务价值去用不同能力的模型
 * 模型能力配置
 */
@Configuration
public class CorpTripModelConfig {

    private final String apiKey;

    /**
     * 从应用配置注入百炼密钥，供对话模型统一使用。
     *
     * @param apiKey YAML 配置中的百炼 API Key
     */
    public CorpTripModelConfig(@Value("${corp.trip.bailian.dashscope-api}") String apiKey) {
        // 统一使用配置提供的凭据，避免在模型创建逻辑中保留密钥字面量。
        this.apiKey = apiKey;
    }

    /**
     * 推理能力比较强的模型
     * 百炼错误码解惑网址：https://help.aliyun.com/zh/model-studio/error-code#error-url
     *
     * @return
     */
    @Bean("strongModel")
    @Primary
    public Model strongModel(){
        // 强推理模型复用从应用配置注入的百炼密钥。
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("kimi-k3.1-pro")
                        .stream(true) // 是否流式输出
                        .endpointType(EndpointType.MULTIMODAL)
                        .formatter(new DashScopeChatFormatter())
                        .build();
        return model;
    }

    /**
     * Flash轻量级模型, 适合 会话标题改写/推荐问题生成等任务
     *
     * @return
     */
    @Bean("lightWeightModel")
    public Model lightWeightModel(){
        // 轻量模型与强推理模型使用同一配置来源，便于统一更换密钥。
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("kimi-k3.1-pro")
                        .stream(true) // 是否流式输出
                        .endpointType(EndpointType.MULTIMODAL) // 使用多模态端点
                        .build();
        return model;
    }


}

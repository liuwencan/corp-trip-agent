package cn.lwccxy.trip.agent.config;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * TODO 后续可以根据任务价值去用不同能力的模型
 * 模型能力配置
 */
@Configuration
public class CorpTripModelConfig {

    private final static String apiKey = "sk-43f3ec833a874f91a52f757fd55b9b9d";

    /**
     * 推理能力比较强的模型
     * 百炼错误码解惑网址：https://help.aliyun.com/zh/model-studio/error-code#error-url
     *
     * @return
     */
    @Bean("strongModel")
    @Primary
    public Model strongModel(){
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3.8-flash")
                        .stream(true) // 是否流式输出
                        .endpointType(EndpointType.MULTIMODAL)
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
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen3.8-flash")
                        .stream(true) // 是否流式输出
                        .endpointType(EndpointType.MULTIMODAL) // 使用多模态端点
                        .build();
        return model;
    }


}

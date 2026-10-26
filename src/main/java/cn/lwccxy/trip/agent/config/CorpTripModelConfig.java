package cn.lwccxy.trip.agent.config;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TODO 后续可以根据任务价值去用不同能力的模型
 * 模型能力配置
 */
@Configuration
public class CorpTripModelConfig {

    private final static String apiKey = "sk-43f3ec833a874f91a52f757fd55b9b9d";

    /**
     * 推理能力比较强的模型
     * @return
     */
    @Bean("strongModel")
    public Model strongModel(){
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("glm-5.3")
                        .stream(true) // 是否流式输出
                        .formatter(new DashScopeChatFormatter())
                        .build();
        return model;
    }



}

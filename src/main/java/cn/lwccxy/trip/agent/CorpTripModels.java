package cn.lwccxy.trip.agent;

import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;

public class CorpTripModels {

    private final static String apiKey = "sk-43f3ec833a874f91a52f757fd55b9b9d";

    DashScopeChatModel model =
            DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("glm-5.3")
                    .stream(true) // 是否流式输出
                    .formatter(new DashScopeChatFormatter())
                    .build();

}

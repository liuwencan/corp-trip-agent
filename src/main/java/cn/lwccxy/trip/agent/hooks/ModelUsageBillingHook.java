package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.enums.ModelPricingEnum;
import cn.lwccxy.trip.agent.utils.ModelTokenBillingUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 记录 AgentScope 每次模型推理的 Token 用量，并按已配置单价估算费用。
 */
public class ModelUsageBillingHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ModelUsageBillingHook.class);

    /**
     * 创建无状态的模型用量计费 Hook。
     */
    public ModelUsageBillingHook() {
    }

    /**
     * 在模型推理完成后读取该次响应的用量，并记录用量及可计算的费用。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            if (!(event instanceof PostReasoningEvent reasoningEvent)) {
                return Mono.just(event);
            }

            ReActAgent agent = (ReActAgent)event.getAgent();
            Memory memory = agent.getMemory();
            List<Msg> messages = memory.getMessages();
            int messageCount = messages == null ? 0 : messages.size();

            // 用量只在推理完成事件中读取，避免将流式增量重复累计。
            Msg response = reasoningEvent.getReasoningMessage();
            ChatUsage usage = response == null ? null : response.getChatUsage();
            if (usage == null) {
                logger.debug("模型响应未提供 Token 用量，agentName={}, model={}",
                        event.getAgent().getName(), reasoningEvent.getModelName());
                return Mono.just(event);
            }

            // 仅按模型名精确匹配已维护的价格，未知型号不套用其他模型单价。
            String modelName = reasoningEvent.getModelName();
            Optional<ModelPricingEnum> pricing = findPricing(modelName);
            BigDecimal cost = pricing.map(model -> ModelTokenBillingUtil.calculateCost(
                    usage.getInputTokens(), usage.getOutputTokens(), model)).orElse(null);
            AgentRuntimeContext runtimeContext = contextView.getOrDefault("agentRuntimeContext", null);
            String traceId = runtimeContext == null ? null : runtimeContext.getTraceId();

            logger.info("[模型用量计费] traceId={}, userId={}, sessionId={}, agentName={}, model={}, messageCount={}, inputTokens={}, outputTokens={}, totalTokens={}, modelTime={}s, estimatedCost={}元",
                    traceId,
                    runtimeContext == null ? null : runtimeContext.getUserId(),
                    runtimeContext == null ? null : runtimeContext.getSessionId(),
                    event.getAgent().getName(), modelName, messageCount,
                    usage.getInputTokens(), usage.getOutputTokens(),
                    usage.getTotalTokens(), usage.getTime(), cost == null ? "未知（未配置单价）" : cost);
            return Mono.just(event);
        });
    }

    /**
     * 按模型服务端名称查找项目中已维护的标准单价。
     *
     * @param modelName 模型服务端名称
     * @return 匹配的价格配置；未配置时返回空
     */
    private Optional<ModelPricingEnum> findPricing(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(ModelPricingEnum.values())
                .filter(pricing -> pricing.getModelName().equals(modelName))
                .findFirst();
    }
}

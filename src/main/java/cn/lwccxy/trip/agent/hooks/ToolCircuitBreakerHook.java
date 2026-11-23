package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.store.toolcircuitbreaker.ToolCircuitBreakerStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 工具熔断
 */
public class ToolCircuitBreakerHook implements Hook {

    private final Logger logger = LoggerFactory.getLogger(ToolCircuitBreakerHook.class);

    public static final String TOOL_CIRCUIT_BREAKER_GROUP = "tool_circuit_breaker_group";
    private final ToolCircuitBreakerStore toolCircuitBreakerStore;

    // 需要熔断的工具名单
    private final static Set<String> NEED_CIRCUIT_BREAKERS_TOOL_NAME = Set.of("query_weather", "query_destination_news");

    public ToolCircuitBreakerHook(ToolCircuitBreakerStore toolCircuitBreakerStore) {
        this.toolCircuitBreakerStore = toolCircuitBreakerStore;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 只监听工具执行结果事件和推理前事件去做处理
        if(event instanceof PostActingEvent postActingEvent){
            return handlePostActing(postActingEvent).map(e -> (T) e);
        }

        if(event instanceof PreReasoningEvent preReasoningEvent){
            return handlePreReasoning(preReasoningEvent).map(e -> (T) e);
        }

        return Mono.just(event);
    }

    // ================== 模型推理前扩展点 ========================
    // 主要作用: 判断受控的工具, 如果处于熔断状态, 并且冷却期过了的话就把工具重新加载回给Agent去使用
    private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent preReasoningEvent) {
        // PreReasoningEvent 没有 getToolkit()，需要从 agent 上取。
        Toolkit toolkit = (preReasoningEvent.getAgent() instanceof ReActAgent reactAgent)
                ? reactAgent.getToolkit()
                : null;

        if (toolkit == null) {
            logger.warn("[ToolCB] PreReasoning 跳过原因=toolkit 为 null, " +
                            "agent 类型={}（非 ReActAgent 或未挂载 toolkit）",
                    preReasoningEvent.getAgent() != null ? preReasoningEvent.getAgent().getClass().getSimpleName() : "null");
            return Mono.just(preReasoningEvent);
        }

        for (String toolName : NEED_CIRCUIT_BREAKERS_TOOL_NAME) {
            // 1. 获取工具的熔断状态
            boolean open = toolCircuitBreakerStore.isOpen(toolName);
            // 2. 获取工具熔断的冷却时长是否已过
            boolean cooldownExpired = toolCircuitBreakerStore.isCooldownExpired(toolName);

            if(open && !cooldownExpired){
                // 如果处于熔断状态 并且 还没有过冷却期, 该工具继续卸载, 对Agent不可见
                applyToolAvailability(toolkit, toolName, true);
            }else{
                // 其他情况, 把工具重新加载回给Agent
                // 1. 熔断状态, 但冷却时长已过
                applyToolAvailability(toolkit, toolName, false);
            }
        }

        return Mono.just(preReasoningEvent);
    }

    // ================== 工具调用结束后扩展点 ========================
    // 主要作用
    // 1. 判断工具是否执行出错, 出错的话就更新对应Redis的计数（失败次数+1, 降级代数+1, 过期时间更新）
    // 2. 如果工具出错达到阈值, 还得把工具从Agent中卸载
    private Mono<PostActingEvent> handlePostActing(PostActingEvent postActingEvent) {
        ToolResultBlock toolResult = postActingEvent.getToolResult();

        // ============ 前置校验 =================
        // 1. 判断工具执行结果是否为空
        if(toolResult == null || toolResult.getName() == null){
            return Mono.just(postActingEvent);
        }

        // 2. 判断当前工具是否是一个受控工具（受控工具需要做熔断处理）
        String toolName = toolResult.getName();
        boolean needCircuitBreaker = toolCircuitBreakerStore.isNeedCircuitBreaker(toolName);
        if(!needCircuitBreaker){
            // 不是受控工具
            return Mono.just(postActingEvent);
        }

        Toolkit toolkit = postActingEvent.getToolkit();

        // =========== 核心处理逻辑 ===============
        // 1. 判断工具执行是否异常
        boolean isError = isException(toolResult);
        // 2. 判断当前工具是否处于熔断状态
        boolean isOpen = toolCircuitBreakerStore.isOpen(toolName);
        // 3. 判断当前工具熔断的冷却期是否已过
        boolean isCooldownExpired = toolCircuitBreakerStore.isCooldownExpired(toolName);

        // OPEN 且冷却期未到：模型应已看不到 tool，但仍兜底（极端时序下可能 LLM 已决策调用）
        if (isOpen && !isCooldownExpired) {
            return Mono.just(postActingEvent);
        }

        // 进入此处时，隐含 cooldownExpired==true，即处于半开期或未熔断状态
        boolean halfOpen = isOpen;

        if(isError){
            if(halfOpen){
                // =========== 半开期 && 工具又执行错误了 ===========
                // 半开期 && 工具又执行错误了, 此时应该降级代数n继续++
                long cooldown = toolCircuitBreakerStore.openWithNextGeneration(toolName);
                // 继续卸载该工具
                applyToolAvailability(toolkit, toolName, true);
                logger.info("[ToolCircuitBreakerHook] 工具熔断半开期再次失败 toolName={}, 重新熔断, 冷却={}s", toolName, cooldown);
            }else{
                // =========== 工具执行错误 && 不处于半开期 ===========
                // 1. 工具执行失败计数+1, 返回的是自增后的结果
                long count = toolCircuitBreakerStore.incrementFailureCount(toolName);
                // 2. 判断工具失败的计数是否大于阈值
                if(count > 3){
                    // 2.1 该工具进入Open状态, 即达到熔断的条件（往Redis里面去新增一个对应的Key）
                    long cooldown = toolCircuitBreakerStore.openWithNextGeneration(toolName);
                    // 2.2 把工具从Agent上卸载下来, 在后续的推理轮次中该工具对Agent不可见
                    applyToolAvailability(toolkit, toolName, true);
                    logger.info("[ToolCircuitBreakerHook] Tool 工具已熔断 toolName={}, 连续失败={}, 冷却={}s", toolName, count, cooldown);
                }
            }
        }else{
            if(halfOpen){
                // ======== 半开期工具执行成功, 清除对应的计数 ======
                toolCircuitBreakerStore.clearOpen(toolName);
                toolCircuitBreakerStore.resetFailureCount(toolName);
                logger.info("[ToolCircuitBreakerHook] Tool 已完全恢复 toolName={}", toolName);
            }else if (toolCircuitBreakerStore.getFailureCount(toolName) > 0){
                // 代表此前这个工具执行失败过, 当前执行成功了, 应该清除计数
                toolCircuitBreakerStore.resetFailureCount(toolName);
                logger.info("[ToolCircuitBreakerHook] Tool当前属于未熔断状态 已清除对应失败的计数 toolName={}, 清除前的失败次数为: {}", toolName, toolCircuitBreakerStore.getFailureCount(toolName));
            }
        }

        return Mono.just(postActingEvent);
    }

    /**
     * 根据 {@code shouldUninstall} 在所有 active group 中将 tool 卸载或加回。
     * 同一 tool 可能被多个 active group 注册（本框架下不常见但理论可能）。
     */
    private void applyToolAvailability(Toolkit toolkit, String toolName, boolean shouldUninstall) {
        // 1. 获取熔断受控工具组
        ToolGroup group = toolkit.getToolGroup(TOOL_CIRCUIT_BREAKER_GROUP);

        if (group == null) {
            return;
        }
        // 2. 判断组内是否有对应工具
        boolean contains = group.containsTool(toolName);
        if (shouldUninstall && contains) {
            try {
                group.removeTool(toolName);
                logger.info("[ToolCircuitBreakerHook] 卸载 tool toolName={}, groupName={}", toolName, TOOL_CIRCUIT_BREAKER_GROUP);
            } catch (Exception e) {
                logger.error("[ToolCircuitBreakerHook] 卸载 tool 失败 toolName={}, groupName={}",
                        toolName, TOOL_CIRCUIT_BREAKER_GROUP, e);
            }
        } else if (!shouldUninstall && !contains) {
            try {
                group.addTool(toolName);
                logger.info("[ToolCircuitBreakerHook] 恢复 tool toolName={}, groupName={}", toolName, TOOL_CIRCUIT_BREAKER_GROUP);
            } catch (Exception e) {
                logger.error("[ToolCircuitBreakerHook] 恢复 tool 失败 toolName={}, groupName={}",
                        toolName, TOOL_CIRCUIT_BREAKER_GROUP, e);
            }
        } else {
            // 状态一致，不需要动作
            logger.debug("[ToolCB] tool 状态已符合预期, toolName={}, groupName={}, " +
                            "contains={}, shouldUninstall={}",
                    toolName, TOOL_CIRCUIT_BREAKER_GROUP, contains, shouldUninstall);
        }
    }

    /**
     * AgentScope框架对异常工具结果统一添加的前缀。
     */
    private static final String ERROR_PREFIX = "Tool execution failed";

    /**
     * 判断工具执行是否异常
     * @return
     */
    public boolean isException(ToolResultBlock toolResult){
        List<ContentBlock> output = toolResult.getOutput();
        if (output == null) {
            return false;
        }
        for (ContentBlock block : output) {
            if (!(block instanceof TextBlock text)) {
                continue;
            }
            String content = text.getText();
            if (content == null) {
                continue;
            }
            if (content.contains(ERROR_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}

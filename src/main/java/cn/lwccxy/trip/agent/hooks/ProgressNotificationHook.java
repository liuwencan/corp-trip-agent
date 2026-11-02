package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.domain.vo.AgentProgressVO;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 将工具执行和智能体文本增量转换为统一 JSON 事件，推送给当前会话的时间线。
 */
public class ProgressNotificationHook implements Hook {

    private static final int HOOK_PRIORITY = 100;
    private static final Logger logger = LoggerFactory.getLogger(ProgressNotificationHook.class);

    // Hook 为共享实例，名称映射只读，避免不同会话并发调用时修改共享状态。
    private static final Map<String, String> TOOL_NAMES = Map.of(
            "get_userinfo", "获取用户信息",
            "ask_user", "请求用户补充信息");

    private static final Map<String, String> AGENT_NAMES = Map.of(
            "MasterAgent", "主智能体",
            "OrderManagementAgent", "行程单智能体",
            "TripOrderManagementAgent", "行程单智能体",
            "GeneralInfoAgent", "通用信息智能体");

    private final SseEmitterRegistry sseEmitterRegistry;

    /**
     * 创建时间线通知 Hook，复用会话已有的 SSE 连接。
     *
     * @param sseEmitterRegistry 会话 SSE 连接注册表
     */
    public ProgressNotificationHook(SseEmitterRegistry sseEmitterRegistry) {
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    /**
     * 返回默认优先级，使进度通知在凭证注入完成后处理工具调用。
     *
     * @return Progress Hook 优先级
     */
    @Override
    public int priority() {
        return HOOK_PRIORITY;
    }

    /**
     * 推送工具开始、工具结果和非空文本增量，始终返回原始事件以继续智能体执行。
     *
     * @param event AgentScope 事件
     * @param <T> 原始事件类型
     * @return 包含原始事件的响应流，JSON 数据通过 SSE 单独发送
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            // 仅使用本次订阅的上下文定位连接，不在共享 Hook 中保存用户或工具调用状态。
            AgentRuntimeContext runtimeContext = contextView.getOrDefault("agentRuntimeContext", null);
            if (runtimeContext == null) {
                return Mono.just(event);
            }
            SseEmitter emitter = sseEmitterRegistry.getEmitter(runtimeContext.getSessionId());
            if (emitter == null) {
                return Mono.just(event);
            }

            AgentProgressVO payload;
            SseEventEnum eventType;
            if (event instanceof PreActingEvent actingEvent) {
                if (actingEvent.getToolUse() == null) {
                    return Mono.just(event);
                }
                eventType = SseEventEnum.TOOL_START;
                payload = createPayload(event, eventType);
                applyToolCall(payload, actingEvent.getToolUse());
            } else if (event instanceof PostActingEvent actingEvent) {
                ToolResultBlock result = actingEvent.getToolResult();
                if (result == null) {
                    return Mono.just(event);
                }
                eventType = SseEventEnum.TOOL_RESULT;
                payload = createPayload(event, eventType);
                // 完成事件自带原始调用，直接获取入参，无需跨事件缓存或按工具名称匹配。
                if (actingEvent.getToolUse() != null) {
                    applyToolCall(payload, actingEvent.getToolUse());
                } else {
                    payload.setToolCallId(result.getId());
                    payload.setToolName(result.getName());
                    payload.setTooldesc(describeTool(result.getName()));
                    payload.setInput(Map.of());
                }
                payload.setOutput(extractText(result.getOutput()));
                // 暂停等待外部处理应与正常返回区分，不能被前端误画为已完成。
                payload.setStatus(result.isSuspended() ? "suspended" : "completed");
            } else if (event instanceof ReasoningChunkEvent reasoningEvent) {
                Msg chunk = reasoningEvent.getIncrementalChunk();
                String text = chunk == null ? null : chunk.getTextContent();
                // 保留有意义的空格增量，仅跳过无文本的工具调用片段，避免破坏文本拼接。
                if (text == null || text.isEmpty()) {
                    return Mono.just(event);
                }
                eventType = SseEventEnum.THINKING;
                payload = createPayload(event, eventType);
                payload.setTooldesc("智能体输出");
                payload.setOutput(text);
                Msg accumulated = reasoningEvent.getAccumulated();
                payload.setMessageId(accumulated == null ? chunk.getId() : accumulated.getId());
            } else {
                return Mono.just(event);
            }

            // 传递 VO 交给 Spring 序列化成 JSON，避免手工拼接或重复 JSON 编码。
            SseEmitterSender.send(emitter, eventType, payload);
            // 入参和结果可能包含个人信息，日志只记录事件类型，不输出业务正文。
            logger.debug("时间线事件已处理，eventType={}", eventType.getCode());
            return Mono.just(event);
        });
    }

    /**
     * 创建所有时间线事件共用的标识、展示名称和事件发生时间。
     *
     * @param event 原始事件
     * @param eventType SSE 事件类型
     * @return 尚未填充工具或文本信息的事件数据
     */
    private AgentProgressVO createPayload(HookEvent event, SseEventEnum eventType) {
        Agent agent = event.getAgent();
        String name = agent.getName();
        AgentProgressVO payload = new AgentProgressVO();
        // 实例标识用于隔离同名智能体，事件标识用于前端区分每一次推送。
        payload.setEventId(UUID.randomUUID().toString());
        payload.setEventType(eventType.getCode());
        payload.setAgentId(agent.getAgentId());
        payload.setAgentName(name == null ? "未知智能体" : AGENT_NAMES.getOrDefault(name, name));
        payload.setTimestamp(event.getTimestamp());
        payload.setStatus("running");
        return payload;
    }

    /**
     * 将原始工具调用写入统一结构，使开始和完成事件携带相同的关联标识和入参。
     *
     * @param payload 待填充的事件数据
     * @param toolUse 工具调用
     */
    private void applyToolCall(AgentProgressVO payload, ToolUseBlock toolUse) {
        payload.setToolCallId(toolUse.getId());
        payload.setToolName(toolUse.getName());
        payload.setTooldesc(describeTool(toolUse.getName()));
        // 入参保持 JSON 对象结构，前端无需再对字符串进行第二次解析。
        payload.setInput(toolUse.getInput() == null ? Map.of() : sanitizeToolInput(toolUse));
    }

    /**
     * 脱敏途牛 Shell 命令中的 API Key，避免将进程环境凭证推送到前端。
     *
     * @param toolUse Shell 工具调用
     * @return 可安全推送的工具输入
     */
    private Map<String, Object> sanitizeToolInput(ToolUseBlock toolUse) {
        if (!"execute_shell_command".equals(toolUse.getName())
                || !(toolUse.getInput().get("command") instanceof String command)
                || !command.contains("TUNIU_API_KEY=")) {
            return toolUse.getInput();
        }

        // 只复制并处理 SSE 展示副本，不修改 AgentScope 实际执行的命令参数。
        Map<String, Object> sanitizedInput = new java.util.HashMap<>(toolUse.getInput());
        sanitizedInput.put("command", command
                .replaceFirst("(?is)(set\\s+\"TUNIU_API_KEY=).*?\"\\s*&&\\s*", "$1[已隐藏]\" && ")
                .replaceFirst("(?is)(export\\s+TUNIU_API_KEY=).*?;\\s*", "$1'[已隐藏]'; "));
        return sanitizedInput;
    }

    /**
     * 获取工具展示名称，兼容作为工具被调用的子智能体，未知名称保留原值。
     *
     * @param toolName 工具原始名称
     * @return 非空的展示描述
     */
    private String describeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "未知工具";
        }
        return AGENT_NAMES.getOrDefault(toolName, TOOL_NAMES.getOrDefault(toolName, toolName));
    }

    /**
     * 按原始顺序提取结果文本，使用固定换行分隔，不将图片等非文本内容转成对象描述。
     *
     * @param output 工具结果内容块
     * @return 合并后的文本，无文本时返回空字符串
     */
    private String extractText(List<ContentBlock> output) {
        if (output == null) {
            return "";
        }
        // 固定提取规则保证相同文本内容和顺序得到相同字符串，不调用模型总结。
        return output.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }
}

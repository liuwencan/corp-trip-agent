package cn.lwccxy.trip.agent.middleware;

import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity;
import cn.lwccxy.trip.business.conversation.mapper.MessageMapper;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.common.util.JsonUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具调用结果持久化Middleware
 */
@Slf4j
public class ToolExecuteStoreMiddleware implements MiddlewareBase {

    private final SseEmitterRegistry sseEmitterRegistry;

    /**
     * 创建工具执行结果中间件。
     *
     * @param sseEmitterRegistry SSE 会话连接注册表
     */
    public ToolExecuteStoreMiddleware(SseEmitterRegistry sseEmitterRegistry) {
        this.sseEmitterRegistry = sseEmitterRegistry;
    }


    /**
     * 在工具执行前推送工具调用信息，并继续执行后续中间件链。
     *
     * @param agent 当前执行的智能体
     * @param ctx 当前运行上下文
     * @param input 当前批次的工具调用
     * @param next 后续中间件或工具执行逻辑
     * @return 工具执行事件流
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String agentName = agent.getName();
        String sessionId = ctx.getSessionId();
        SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);

        // emitter 不存在时跳过进度推送，但保留 AgentScope 原有的工具执行流程。
        List<ToolUseBlock> toolUseBlocks = input.toolCalls();
        if (emitter == null) {
            log.warn("[ToolExecuteStoreMiddleware] SSE连接不存在，跳过工具进度推送，sessionId={}", sessionId);
        } else if (agentName == null || agentName.isBlank()) {
            log.warn("[ToolExecuteStoreMiddleware] 智能体名称为空，跳过工具进度推送，sessionId={}", sessionId);
        } else if (toolUseBlocks == null || toolUseBlocks.isEmpty()) {
            log.warn("[ToolExecuteStoreMiddleware] 工具调用列表为空，sessionId={}", sessionId);
        } else {
            // 逐条校验并发送，单条数据异常不会中断同一批次中的其他工具。
            for (ToolUseBlock toolUseBlock : toolUseBlocks) {
                if (toolUseBlock == null) {
                    log.warn("[ToolExecuteStoreMiddleware] 工具调用为空，sessionId={}", sessionId);
                    continue;
                }

                String toolCallId = toolUseBlock.getId();
                String toolName = toolUseBlock.getName();
                if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
                    log.warn("[ToolExecuteStoreMiddleware] 工具调用缺少必需字段，sessionId={}, toolCallId={}, toolName={}",
                            sessionId, toolCallId, toolName);
                    continue;
                }

                try {
                    Map<String, Object> toolInput = toolUseBlock.getInput();
                    String toolInputJson = JsonUtil.toJson(toolInput == null ? Map.of() : toolInput);
                    if (toolInputJson == null) {
                        toolInputJson = "{}";
                    }
                    // 推送工具开始执行事件
                    SseEmitterSender.send(emitter, SseEventEnum.TOOL_START,
                            Map.of("agentName", agentName, "toolCallId", toolCallId, "toolName", toolName,
                                    "input", toolInputJson));
                } catch (Exception e) {
                    // 不记录工具参数，避免业务输入进入日志；保留异常堆栈用于区分数据错误和连接错误。
                    log.error("[ToolExecuteStoreMiddleware] 工具进度推送失败，sessionId={}, toolCallId={}, toolName={}",
                            sessionId, toolCallId, toolName, e);
                }
            }
        }

        // 工具执行结果按增量事件推送；推送异常只影响当前 SSE 事件，不中断工具结果流。
        // 没有打印到
        return next.apply(input)
                .map(event -> {
                    if(event instanceof ToolResultStartEvent toolResultStartEvent){
                        // 工具开始执行
                    }else if (event instanceof ToolResultTextDeltaEvent textResultEvent) {
                        // 如果当前工具是ASKUSER就不做推送对应的执行结果
                        String toolCallName = textResultEvent.getToolCallName();
                        if(toolCallName.equals("ask_user")) return event;

                        // 工具结果增量来自 Agent 顶层事件流，按工具调用 ID 推送给前端关联。
                        SseEmitterSender.send(emitter, SseEventEnum.TOOL_RESULT, eventPayload(Long.valueOf(sessionId),
                                "toolCallId", textResultEvent.getToolCallId(), "toolResult", textResultEvent.getDelta()));
                    } else if (event instanceof ToolResultDataDeltaEvent dataResultEvent) {
                        // 如果当前工具是ASKUSER就不做推送对应的执行结果
                        String toolCallName = dataResultEvent.getToolCallName();
                        if(toolCallName.equals("ask_user")) return event;

                        // MCP 等工具可能返回结构化内容，不能只监听文本结果事件。
                        SseEmitterSender.send(emitter, SseEventEnum.TOOL_RESULT, eventPayload(Long.valueOf(sessionId),
                                "toolCallId", dataResultEvent.getToolCallId(), "toolResult", dataResultEvent.getData()));
                    } else if (event instanceof ToolResultEndEvent resultEndEvent) {
                        // 如果当前工具是ASKUSER就不做推送对应的执行结果
                        String toolCallName = resultEndEvent.getToolCallName();
                        if(toolCallName.equals("ask_user")) return event;

                        // 发送终态以便前端区分结果增量结束和工具调用完成。
                        SseEmitterSender.send(emitter, SseEventEnum.TOOL_SUCCESS, eventPayload(Long.valueOf(sessionId),
                                "toolCallId", resultEndEvent.getToolCallId(), "phase", "completed",
                                "state", resultEndEvent.getState()));
                    }
                    return event;
                });
    }

    /** 构造允许空值的 SSE 事件数据，避免 AgentScope 可选字段导致 Map.of 抛出异常。 */
    private Map<String, Object> eventPayload(Long conversationId, Object... fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        for (int index = 0; index < fields.length; index += 2) {
            payload.put((String) fields[index], fields[index + 1]);
        }
        return payload;
    }

    /**
     * 推送单个工具结果增量。
     *
     * @param emitter 当前 SSE 连接
     * @param sessionId 当前会话标识
     * @param toolCallId 工具调用标识
     * @param result 工具结果增量，可以是文本或结构化内容
     */
    private void sendToolResult(SseEmitter emitter, String sessionId, String toolCallId, Object result) {
        if (emitter == null) {
            log.warn("[ToolExecuteStoreMiddleware] SSE连接不存在，跳过工具结果推送，sessionId={}, toolCallId={}",
                    sessionId, toolCallId);
            return;
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            log.warn("[ToolExecuteStoreMiddleware] 工具结果缺少toolCallId，sessionId={}", sessionId);
            return;
        }

        try {
            // 使用可变 Map 允许结果内容为空，避免 Map.of 因空值抛出异常。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", toolCallId);
            payload.put("toolResult", result);
            SseEmitterSender.send(emitter, SseEventEnum.TOOL_RESULT, payload);
        } catch (Exception e) {
            log.error("[ToolExecuteStoreMiddleware] 工具结果推送失败，sessionId={}, toolCallId={}",
                    sessionId, toolCallId, e);
        }
    }
}

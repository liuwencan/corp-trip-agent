package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.business.conversation.enums.MessageRole;
import cn.lwccxy.trip.business.conversation.service.ConversationService;
import cn.lwccxy.trip.common.util.JsonUtil;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConfirmationHook implements Hook {
    private final Logger logger = LoggerFactory.getLogger(ConfirmationHook.class);
    private final SseEmitterRegistry sseEmitterRegistry;
    private final ConversationService conversationService;

    /**
     * 创建确认 Hook。
     *
     * @param sseEmitterRegistry SSE 连接注册表
     * @param conversationService 会话消息持久化服务
     */
    public ConfirmationHook(SseEmitterRegistry sseEmitterRegistry, ConversationService conversationService) {
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.conversationService = conversationService;
    }


    private final String ASK_USER = "ask_user";

    /**
     * 处理推理完成事件，提取 ask_user 工具参数并通知前端等待用户输入。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            // 优先从 Reactor Context 获取，未传入时使用 Agent 创建时保存的上下文
            AgentRuntimeContext runtimeContext =
                    contextView.getOrDefault("agentRuntimeContext", null);

            if (event instanceof PostReasoningEvent e) {
                logger.info("ConfirmationHook开始执行");

                String sessionId = runtimeContext.getSessionId();
                SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);

                Msg reasoningMsg = e.getReasoningMessage();
                List<ToolUseBlock> toolCalls =
                        reasoningMsg.getContentBlocks(ToolUseBlock.class);

                Optional<ToolUseBlock> askUserTool = toolCalls.stream()
                        .filter(toolCall -> ASK_USER.equals(toolCall.getName()))
                        .findFirst();

                if (askUserTool.isPresent()) {
                    ToolUseBlock toolCall = askUserTool.get();
                    Map<String, Object> input = toolCall.getInput();
                    String toolCallId = toolCall.getId();
                    // 将等待确认的工具调用写入会话历史，便于后续按时间顺序查询完整交互。
                    Map<String, Object> persistedContent = new LinkedHashMap<>();
                    persistedContent.put("toolCallId", toolCallId);
                    persistedContent.put("input", input);
                    Long userId = Long.valueOf(runtimeContext.getUserId());
                    Long conversationId = Long.valueOf(sessionId);
                    conversationService.append(userId, conversationId, MessageRole.TOOL,
                            JsonUtil.toJson(persistedContent), null);

                    if (emitter != null) {
                        // 将工具调用标识和用户澄清参数一起发送，便于前端渲染并在恢复调用时关联原工具请求。
                        SseEmitterSender.send(
                                emitter,
                                SseEventEnum.ASK,
                                persistedContent
                        );
                    }
                    e.stopAgent();
                }
            }

            return Mono.just(event);
        });
    }

}

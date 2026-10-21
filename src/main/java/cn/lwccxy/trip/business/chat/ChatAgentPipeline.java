package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.agent.middleware.HumanInTheLoopMiddleware;
import cn.lwccxy.trip.business.conversation.domain.entity.ConversationEntity;
import cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity;
import cn.lwccxy.trip.business.conversation.domain.form.CreateConversationForm;
import cn.lwccxy.trip.business.conversation.domain.vo.ConversationVO;
import cn.lwccxy.trip.business.conversation.domain.vo.MessageVO;
import cn.lwccxy.trip.business.conversation.enums.ConversationErrorCode;
import cn.lwccxy.trip.business.conversation.enums.MessageRole;
import cn.lwccxy.trip.business.conversation.mapper.ConversationMapper;
import cn.lwccxy.trip.business.conversation.mapper.MessageMapper;
import cn.lwccxy.trip.business.conversation.service.ConversationCoordinator;
import cn.lwccxy.trip.business.conversation.service.ConversationService;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.common.sse.SseEmitterRegistry;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** 负责执行主智能体流式对话，并将事件推送给调用方。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatAgentPipeline {
    private final ConversationService conversations;
    private final ConversationCoordinator coordinator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactions;
    private final SseEmitterRegistry sseEmitterRegistry;

    /** 为当前用户执行一次会话，先保存用户消息，再通过服务端事件推送智能体响应。 */
    public SseEmitter execute(Long userId, Long conversationId, String question) {
        validateQuestion(question);
        ConversationEntity conversation = ensureConversation(userId, conversationId, question);
        return executeStream(userId, conversation, question,
                (agent, runtimeContext) -> agent.streamEvents(new UserMessage(question), runtimeContext));
    }

    /** 将用户对澄清问题的回答作为工具结果提交，并恢复指定的智能体会话。 */
    public SseEmitter resume(Long userId, Long conversationId, String toolCallId, String answer) {
        validateQuestion(answer);
        if (conversationId == null || conversationId <= 0 || toolCallId == null || toolCallId.isBlank()) {
            throw new BizException(ConversationErrorCode.INVALID_PARAMETER);
        }
        ConversationEntity conversation = conversations.owned(userId, conversationId);

        return executeStream(userId, conversation, answer,
                (agent, runtimeContext) -> resumeAgent(agent, runtimeContext, toolCallId, answer));

        // 注册SSEEmitter


    }

    /** 持久化当前消息并启动一次 AgentScope 流式执行。 */
    private SseEmitter executeStream(Long userId, ConversationEntity conversation, String question,
                                     BiFunction<ReActAgent, RuntimeContext, Flux<AgentEvent>> streamFactory) {
        Long actualConversationId = conversation.getId();
        ConversationCoordinator.Lease lease = coordinator.acquire(actualConversationId, true);
        String traceId = UUID.randomUUID().toString();
        try {
            // 先保存用户问题，确保智能体创建或调用失败时仍保留本轮输入。
            MessageVO userMessage = question == null ? null : append(userId, actualConversationId,
                    MessageRole.USER, question, null, traceId);
            SseEmitter emitter = new SseEmitter(240_000L);
            // 让其他业务能够按用户查找当前对话连接并推送异步事件。
            sseEmitterRegistry.register(userId, emitter);
            AtomicBoolean terminal = new AtomicBoolean();
            AtomicBoolean waitingForUser = new AtomicBoolean();
            AtomicReference<Disposable> subscription = new AtomicReference<>();
            AtomicReference<String> answer = new AtomicReference<>();
            emitter.onTimeout(() -> cancel(lease, terminal, subscription));
            emitter.onError(error -> cancel(lease, terminal, subscription));
            emitter.onCompletion(() -> cancel(lease, terminal, subscription));

            if (userMessage == null) {
                sendEvent(emitter, "init", Map.of("conversationId", actualConversationId));
            } else {
                sendEvent(emitter, "init", Map.of("conversationId", actualConversationId,
                        "messageId", userMessage.getId()));
            }
            RuntimeContext runtimeContext = RuntimeContext.builder().userId(userId.toString())
                    .sessionId(conversation.getRuntimeSessionId()).build();
            // 将工具参数交给当前 SSE 连接，供前端构建澄清交互界面。
            runtimeContext.put(HumanInTheLoopMiddleware.ASK_USER_CALLBACK_KEY,
                    (Consumer<ToolUseBlock>) tool -> {
                        waitingForUser.set(true);
                        sendEvent(emitter, "ask", Map.of("conversationId", actualConversationId,
                                "toolCallId", tool.getId(), "input", tool.getInput(),
                                "delta", tool.getInput()));
                    });

            Disposable active = Flux.using(
                            () -> SpringContextUtil.getBean("masterAgent", ReActAgent.class),
                            agent -> streamFactory.apply(agent, runtimeContext),
                            ReActAgent::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    // 在事件到达时转发增量内容并记录最终回复。
                    .doOnNext(event -> handleEvent(emitter, actualConversationId, answer, waitingForUser, event))
                    .doOnError(error -> handleError(emitter, lease, terminal, actualConversationId, error))
                    .onErrorComplete()
                    .doOnComplete(() -> handleComplete(emitter, lease, terminal, actualConversationId,
                            userId, traceId, answer.get(), waitingForUser.get()))
                    .subscribe();
            subscription.set(active);
            if (terminal.get()) {
                active.dispose();
            }
            return emitter;
        } catch (RuntimeException ex) {
            lease.close();
            throw ex;
        }
    }

    /** 根据持久化的工具调用构造成功结果，交给 AgentScope 恢复挂起的智能体。 */
    private Flux<AgentEvent> resumeAgent(ReActAgent agent, RuntimeContext runtimeContext,
                                         String toolCallId, String answer) {
        // 从数据库恢复当前会话，确保恢复请求只能对应仍处于挂起状态的 ask_user 调用。
        AgentState state = agent.getAgentState(runtimeContext);
        if (state == null || state.getContext() == null) {
            throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
        }
        ToolUseBlock toolCall = state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(tool -> toolCallId.equals(tool.getId()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND));
        boolean alreadyAnswered = state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(result -> toolCallId.equals(result.getId()) && !result.isSuspended());
        if (!"ask_user".equals(toolCall.getName()) || alreadyAnswered) {
            throw new BizException(ConversationErrorCode.INVALID_PARAMETER);
        }

        // 使用 AgentScope 消息构造器关联工具调用与结果，使模型从挂起点继续推理。
        ToolResultBlock toolResult = ToolResultBlock.text(answer)
                .withIdAndName(toolCall.getId(), toolCall.getName())
                .withState(ToolResultState.SUCCESS);
        Msg toolResultMessage = ToolResultMessageBuilder.buildToolResultMsg(
                toolResult, toolCall, agent.getName());
        // 直接更新同一个 AgentState，兼容 AgentScope 2.0.0 对挂起工具结果的恢复判断。
        replaceSuspendedToolResult(state, toolResult, toolResultMessage);
        agent.saveAgentState(runtimeContext);
        return agent.streamEvents(List.of(), runtimeContext);
    }

    /** 在持久化上下文中替换挂起的工具结果，缺少结果时追加一条工具消息。 */
    private void replaceSuspendedToolResult(AgentState state, ToolResultBlock replacement,
                                            Msg replacementMessage) {
        List<Msg> context = state.contextMutable();
        for (int messageIndex = context.size() - 1; messageIndex >= 0; messageIndex--) {
            Msg message = context.get(messageIndex);
            List<ContentBlock> blocks = message.getContent();
            for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                ContentBlock block = blocks.get(blockIndex);
                if (block instanceof ToolResultBlock result
                        && replacement.getId().equals(result.getId()) && result.isSuspended()) {
                    // 保留原消息的角色和元数据，只替换挂起状态的工具结果内容。
                    List<ContentBlock> updatedBlocks = new java.util.ArrayList<>(blocks);
                    updatedBlocks.set(blockIndex, replacement);
                    context.set(messageIndex, message.withContent(updatedBlocks));
                    return;
                }
            }
        }
        // 某些 AgentScope 版本不会把挂起结果写入状态库，此时补充完整的工具结果消息。
        context.add(replacementMessage);
    }

    /** 校验会话归属；不存在时根据首个问题创建新的活动会话。 */
    private ConversationEntity ensureConversation(Long userId, Long conversationId, String question) {
        ConversationEntity conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation != null) {
            if (!userId.equals(conversation.getUserId())) {
                throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
            }
            return conversation;
        }
        CreateConversationForm form = new CreateConversationForm();
        form.setTitle(firstQuestionTitle(question));
        ConversationVO created = conversations.create(userId, form);
        return conversations.owned(userId, created.getId());
    }

    /** 将首个问题截取前五个字符作为会话标题。 */
    private String firstQuestionTitle(String question) {
        String normalized = question.strip();
        int characterCount = Math.min(5, normalized.codePointCount(0, normalized.length()));
        return normalized.substring(0, normalized.offsetByCodePoints(0, characterCount));
    }

    /** 校验问题内容，避免空问题或超长问题进入智能体流程。 */
    private void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > 20000) {
            throw new BizException(ConversationErrorCode.INVALID_PARAMETER);
        }
    }

    /** 将智能体增量事件推送给前端，并保存最终 assistant 回复。 */
    private void handleEvent(SseEmitter emitter, Long conversationId,
                             AtomicReference<String> answer, AtomicBoolean waitingForUser,
                             AgentEvent agentEvent) {
        // 增量文本
        if (agentEvent instanceof TextBlockDeltaEvent text) {
            sendEvent(emitter, "delta", Map.of("conversationId", conversationId,
                    "delta", text.getDelta()));
            return;
        }

        if (agentEvent instanceof RequireUserConfirmEvent requireUserConfirmEvent) {
            List<ToolUseBlock> toolCalls = requireUserConfirmEvent.getToolCalls();
            // 权限确认事件用于需要人工批准的工具，澄清问题参数由中间件单独推送。
            sendEvent(emitter, "confirm", Map.of("conversationId", conversationId,
                    "toolCalls", toolCalls));
            return;
        }

        if (agentEvent instanceof AgentResultEvent resultEvent) {
            var message = resultEvent.getResult();
            if (message == null) {
                throw new BizException(ConversationErrorCode.AGENT_FAILED);
            }
            GenerateReason reason = message.getGenerateReason();
            if (reason == GenerateReason.TOOL_SUSPENDED) {
                // 问题已通过 ask 事件发给前端，本轮等待用户补充输入。
                waitingForUser.set(true);
                return;
            }
            if (message.getRole() != MsgRole.ASSISTANT
                    || (reason != null && reason != GenerateReason.MODEL_STOP
                    && reason != GenerateReason.STRUCTURED_OUTPUT)) {
                throw new BizException(ConversationErrorCode.AGENT_FAILED);
            }
            answer.set(message.getTextContent());
        }
    }

    /** 处理智能体流正常结束，持久化 assistant 回复并发送完成事件。 */
    private void handleComplete(SseEmitter emitter, ConversationCoordinator.Lease lease,
                                AtomicBoolean terminal, Long conversationId, Long userId,
                                String traceId, String answer, boolean waitingForUser) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            if (waitingForUser) {
                emitter.complete();
                return;
            }
            if (answer == null || answer.isBlank()) {
                throw new BizException(ConversationErrorCode.AGENT_FAILED);
            }
            MessageVO reply = append(userId, conversationId, MessageRole.ASSISTANT,
                    answer, modelName(), traceId);
            sendEvent(emitter, "complete", Map.of("conversationId", conversationId,
                    "messageId", reply.getId(), "message", reply));
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Agent conversation {} failed", conversationId, ex);
            sendError(emitter);
            emitter.complete();
        } finally {
            lease.close();
        }
    }

    /** 处理智能体流异常，向前端发送错误事件并释放会话锁。 */
    private void handleError(SseEmitter emitter, ConversationCoordinator.Lease lease,
                             AtomicBoolean terminal, Long conversationId, Throwable error) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        log.warn("Agent conversation {} failed", conversationId, error);
        try {
            sendError(emitter);
            emitter.complete();
        } finally {
            lease.close();
        }
    }

    /** 客户端超时、断开或完成时取消智能体订阅并释放会话锁。 */
    private void cancel(ConversationCoordinator.Lease lease, AtomicBoolean terminal,
                        AtomicReference<Disposable> subscription) {
        if (terminal.compareAndSet(false, true)) {
            Disposable active = subscription.get();
            if (active != null) {
                active.dispose();
            }
            lease.close();
        }
    }

    /** 向前端发送统一的智能体失败事件，忽略已断开的客户端连接。 */
    private void sendError(SseEmitter emitter) {
        try {
            sendEvent(emitter, "error", Response.fail(ConversationErrorCode.AGENT_FAILED));
        } catch (RuntimeException ignored) {
            // 客户端连接已断开时无法继续发送错误事件。
        }
    }

    /** 读取当前模型名称，用于记录 assistant 消息来源。 */
    private String modelName() {
        return SpringContextUtil.getBean("strongModel", Model.class).getModelName();
    }

    /** 在同一个事务中保存消息并更新所属会话的活动字段。 */
    private MessageVO append(Long userId, Long conversationId, MessageRole role, String content,
                             String modelName, String traceId) {
        return transactions.execute(tx -> {
            ConversationEntity conversation = conversationMapper.lockOwned(userId, conversationId);
            if (conversation == null) {
                throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!"ACTIVE".equals(conversation.getStatus())) {
                throw new BizException(ConversationErrorCode.CONVERSATION_STATE_INVALID);
            }
            // 原子保存消息并递增会话计数，确保历史记录和会话统计保持一致。
            MessageEntity entity = new MessageEntity();
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setRole(role.name());
            entity.setContent(content);
            entity.setModelName(modelName);
            entity.setCreatedBy(userId);
            if (messageMapper.insert(entity) != 1
                    || conversationMapper.appended(userId, conversationId, traceId) != 1) {
                throw new IllegalStateException("Message persistence failed");
            }
            MessageEntity saved = messageMapper.lockOwned(userId, conversationId, entity.getId());
            MessageVO result = new MessageVO();
            BeanUtils.copyProperties(saved, result);
            return result;
        });
    }

    /** 发送一个命名服务端事件，并将传输异常交给订阅错误处理。 */
    private void sendEvent(SseEmitter emitter, String name, Object value) {
        try {
            emitter.send(SseEmitter.event().name(name).data(value));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

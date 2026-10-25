package cn.lwccxy.trip.business.chat;

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
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.middleware.HumanInTheLoopMiddleware;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        // 1. 问题校验
        validateQuestion(question);
        // 2. 会话创建
        ConversationEntity conversation = ensureConversation(userId, conversationId, question);
        // 3. 问题持久化
        MessageVO userMessage = question == null ? null : append(userId, conversationId,
                MessageRole.USER, question, null);
        // 4. 注册SSE
        SseEmitter emitter = new SseEmitter(240_000L);
        sseEmitterRegistry.register(conversationId.toString(), emitter);
        // 构建运行时上下文并沿用会话已有的 AgentScope session。

        String traceId = UUID.randomUUID().toString();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(userId.toString())
                .sessionId(conversationId.toString())
                .put("traceId", traceId).build();


        StringBuilder answer = new StringBuilder();
        AtomicBoolean receivedFinalResult = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();

        // 主智能体
        ReActAgent masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);
        Flux<AgentEvent> agentEventFlux = masterAgent.streamEvents(List.of(new UserMessage(question)), runtimeContext);
        agentEventFlux.subscribe(event -> {
            if (terminal.get()) {
                return;
            }

            if (event instanceof TextBlockDeltaEvent textEvent) {
                // 按模型返回顺序拼接文本增量，使流结束后可以得到完整回答。
                String delta = textEvent.getDelta();
                if (delta != null) {
                    answer.append(delta);
                }
                SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
            } else if (event instanceof AgentResultEvent resultEvent) {
                Msg result = resultEvent.getResult();
                GenerateReason reason = result == null ? null : result.getGenerateReason();

                if(reason == GenerateReason.TOOL_SUSPENDED){
                    // ASK_USER工具处理, 工具内部抛出了异常
                    ToolResultMessage toolResult = (ToolResultMessage) result;
                    log.info(toolResult.toString());
                }

                if (result == null || result.getRole() != MsgRole.ASSISTANT
                        || (reason != null && reason != GenerateReason.MODEL_STOP
                        && reason != GenerateReason.STRUCTURED_OUTPUT)) {
                    if (terminal.compareAndSet(false, true)) {
                        sendError(emitter);
                        emitter.complete();
                    }
                    return;
                }

                // 最终消息覆盖可能包含中间轮次内容的增量缓存，作为持久化正文的权威结果。
                String resultText = result.getTextContent();
                log.info("本轮对话完毕, Agent最终输出的结果是： {}", resultText);

                // 仅在收到合法的最终 assistant 结果后落库并发送完成事件。
                append(userId, conversationId, MessageRole.ASSISTANT, resultText, modelName());
                sendEvent(emitter, SseEventEnum.COMPLETE, Map.of("conversationId", conversationId));
                emitter.complete();
            }
        }, throwable -> {
            log.warn("[ChatAgentPipeline] Agent conversation {} failed", conversationId, throwable);
            if (terminal.compareAndSet(false, true)) {
                sendError(emitter);
                emitter.complete();
            }
        });

        return emitter;
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
    }

    /** 持久化当前消息并启动一次 AgentScope 流式执行。 */
    private SseEmitter executeStream(Long userId, ConversationEntity conversation, String question,
                                     BiFunction<ReActAgent, RuntimeContext, Flux<AgentEvent>> streamFactory) {
        Long conversationId = conversation.getId();
        String sessionId = conversation.getRuntimeSessionId();
        ConversationCoordinator.Lease lease = coordinator.acquire(conversationId, true);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean waitingForUser = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();
        SseEmitter emitter = new SseEmitter(240_000L);

        try {
            // 保存本轮输入并登记会话连接，使新消息与恢复消息共用相同的生命周期管理。
            MessageVO userMessage = question == null ? null : append(userId, conversationId,
                    MessageRole.USER, question, null);
            sseEmitterRegistry.register(sessionId, emitter);
            emitter.onTimeout(() -> cancel(lease, terminal, subscription));
            emitter.onError(error -> cancel(lease, terminal, subscription));
            emitter.onCompletion(() -> cancel(lease, terminal, subscription));
            if (userMessage == null) {
                sendEvent(emitter, SseEventEnum.INIT, Map.of("conversationId", conversationId));
            } else {
                sendEvent(emitter, SseEventEnum.INIT, Map.of("conversationId", conversationId,
                        "messageId", userMessage.getId()));
            }

            // 构建运行时上下文并沿用会话已有的 AgentScope session。
            RuntimeContext runtimeContext = RuntimeContext.builder().userId(userId.toString())
                    .sessionId(conversation.getRuntimeSessionId()).build();

            // 主智能体
            ReActAgent masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);

            Disposable active = Flux.using(
                            () -> masterAgent,
                            agent -> streamFactory.apply(agent, runtimeContext),
                            ReActAgent::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    // 将事件增量推送给前端，并收集最终回复及人工澄清状态。
                    .doOnNext(event -> handleEvent(emitter, conversationId, answer, waitingForUser, event))
                    .doOnError(error -> handleError(emitter, lease, terminal, conversationId, error))
                    .onErrorComplete()
                    .doOnComplete(() -> handleComplete(emitter, lease, terminal, conversationId,
                            userId, answer.get(), waitingForUser.get()))
                    .subscribe();
            subscription.set(active);
            if (terminal.get()) {
                active.dispose();
            }
        } catch (RuntimeException ex) {
            lease.close();
            throw ex;
        }

        return emitter;
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
        // 1. 查询会话是否存在
        ConversationEntity conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        // 2. 存在, 判断会话归属
        if (conversation != null) {
            if (!userId.equals(conversation.getUserId())) {
                throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
            }
            return conversation;
        }
        CreateConversationForm form = new CreateConversationForm();
        // 不存在, 先截取用户第一个问题的前五个字符, 先进行持久化
        // 后续通过异步线程利用【轻量级大模型】去做标题摘要
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
    private void handleEvent(SseEmitter emitter, Long conversationId, AtomicReference<String> answer,
                             AtomicBoolean waitingForUser, AgentEvent agentEvent) {
        if (agentEvent instanceof AgentStartEvent event) {
            sendEvent(emitter, SseEventEnum.STATUS, eventPayload(conversationId, "status", "EXECUTING",
                    "agentName", event.getName(), "message", "Agent开始执行"));
            return;
        }
        if (agentEvent instanceof AgentEndEvent) {
            sendEvent(emitter, SseEventEnum.STATUS, eventPayload(conversationId, "status", "AGENT_FINISHED"));
            return;
        }
        if (agentEvent instanceof ModelCallStartEvent event) {
            sendEvent(emitter, SseEventEnum.STATUS, eventPayload(conversationId, "status", "THINKING",
                    "replyId", event.getReplyId()));
            return;
        }
        if (agentEvent instanceof ModelCallEndEvent event) {
            sendEvent(emitter, SseEventEnum.STATUS, eventPayload(conversationId, "status", "MODEL_FINISHED",
                    "replyId", event.getReplyId(), "usage", event.getUsage()));
            return;
        }
        if (agentEvent instanceof ThinkingBlockStartEvent event) {
            sendEvent(emitter, SseEventEnum.THINKING, eventPayload(conversationId, "phase", "start",
                    "replyId", event.getReplyId(), "blockId", event.getBlockId()));
            return;
        }
        if (agentEvent instanceof ThinkingBlockDeltaEvent event) {
            sendEvent(emitter, SseEventEnum.THINKING, eventPayload(conversationId, "phase", "delta",
                    "replyId", event.getReplyId(), "blockId", event.getBlockId(), "delta", event.getDelta()));
            return;
        }
        if (agentEvent instanceof ThinkingBlockEndEvent event) {
            sendEvent(emitter, SseEventEnum.THINKING, eventPayload(conversationId, "phase", "end",
                    "replyId", event.getReplyId(), "blockId", event.getBlockId()));
            return;
        }


        if (agentEvent instanceof AgentResultEvent event) {
            Msg message = event.getResult();
            if (message == null) {
                throw new BizException(ConversationErrorCode.AGENT_FAILED);
            }
            GenerateReason reason = message.getGenerateReason();
            if (reason == GenerateReason.TOOL_SUSPENDED) {
                // ask 事件已提供澄清内容，本轮应等待用户提交答案后再继续执行。
                waitingForUser.set(true);
                return;
            }
            for (ThinkingBlock thinking : message.getContentBlocks(ThinkingBlock.class)) {
                sendEvent(emitter, SseEventEnum.THINKING, eventPayload(conversationId, "phase", "complete",
                        "thinking", thinking.getThinking(), "metadata", thinking.getMetadata()));
            }
            if (message.getRole() != MsgRole.ASSISTANT
                    || (reason != null && reason != GenerateReason.MODEL_STOP
                    && reason != GenerateReason.STRUCTURED_OUTPUT)) {
                throw new BizException(ConversationErrorCode.AGENT_FAILED);
            }
            answer.set(message.getTextContent());
            return;
        }
        // 保留未专门处理的 AgentScope 事件，确保前端不会因版本扩展而丢失执行轨迹。
        sendEvent(emitter, SseEventEnum.AGENT_EVENT, eventPayload(conversationId,
                "type", agentEvent.getType().getValue(), "eventId", agentEvent.getId(),
                "source", agentEvent.getSource(), "metadata", agentEvent.getMetadata()));
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

    /** 处理智能体流正常结束，持久化 assistant 回复并发送完成事件。 */
    private void handleComplete(SseEmitter emitter, ConversationCoordinator.Lease lease,
                                AtomicBoolean terminal, Long conversationId, Long userId,
                                String answer, boolean waitingForUser) {
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
                    answer, modelName());
            sendEvent(emitter, SseEventEnum.COMPLETE, Map.of("conversationId", conversationId,
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
            sendEvent(emitter, SseEventEnum.ERROR, Response.fail(ConversationErrorCode.AGENT_FAILED));
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
                             String modelName) {
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
                    || conversationMapper.appended(userId, conversationId) != 1) {
                throw new IllegalStateException("Message persistence failed");
            }
            MessageEntity saved = messageMapper.lockOwned(userId, conversationId, entity.getId());
            MessageVO result = new MessageVO();
            BeanUtils.copyProperties(saved, result);
            return result;
        });
    }

    /** 发送一个命名服务端事件，并将传输异常交给订阅错误处理。 */
    private void sendEvent(SseEmitter emitter, SseEventEnum event, Object value) {
        SseEmitterSender.send(emitter, event, value);
    }
}

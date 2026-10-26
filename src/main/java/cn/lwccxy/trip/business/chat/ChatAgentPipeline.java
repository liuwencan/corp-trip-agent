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
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.*;
import io.agentscope.core.model.Model;
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

        StringBuilder answer = new StringBuilder();
        AtomicBoolean receivedFinalResult = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();

        // 主智能体
        ReActAgent masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);

        // 用户发送的消息
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .textContent(question)
                .build();

        Flux<Event> events = masterAgent.stream(List.of(userMsg), StreamOptions.defaults());
        events.subscribe(event -> {
            if (terminal.get()) {
                return;
            }
            // 推理事件
            if(event.getType() == EventType.REASONING){
                String delta = event.getMessage().getTextContent();
                if (delta != null) {
                    answer.append(delta);
                }
                SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
            } else if (event.getType() == EventType.AGENT_RESULT) {
                Msg result = event.getMessage();
                GenerateReason reason = result == null ? null : result.getGenerateReason();

                if(reason == GenerateReason.TOOL_SUSPENDED){
                    // ASK_USER工具处理, 工具内部抛出了异常
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

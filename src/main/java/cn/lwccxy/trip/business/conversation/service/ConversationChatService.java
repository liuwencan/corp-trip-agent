package cn.lwccxy.trip.business.conversation.service;

import cn.lwccxy.trip.business.conversation.domain.vo.MessageVO;
import cn.lwccxy.trip.business.conversation.enums.*;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.Response;
import io.agentscope.core.event.*;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MsgRole;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationChatService {
    private final ConversationService conversations;
    private final ConversationCoordinator coordinator;
    private final AgentReplyGateway gateway;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SseEmitter send(Long userId, Long id, String content) {
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw new BizException(ConversationErrorCode.INVALID_PARAMETER);
        }
        conversations.owned(userId, id);
        var lease = coordinator.acquire(id, true);
        try {
            var conversation = conversations.owned(userId, id);
            String traceId = UUID.randomUUID().toString();
            MessageVO userMessage = conversations.append(userId, id, MessageRole.USER, content, null, traceId);
            SseEmitter emitter = new SseEmitter(240_000L);
            AtomicBoolean terminal = new AtomicBoolean();
            AtomicReference<Thread> worker = new AtomicReference<>();
            Runnable work = () -> {
                worker.set(Thread.currentThread());
                try (lease) {
                    if (terminal.get()) return;
                    sendEvent(emitter, "init", Map.of("conversationId", id, "messageId", userMessage.getId()));
                    AtomicReference<String> result = new AtomicReference<>();
                    gateway.stream(userId, conversation.getRuntimeSessionId(), content)
                            .doOnNext(event -> {
                                if (event instanceof TextBlockDeltaEvent text) {
                                    sendEvent(emitter, "delta", Map.of("conversationId", id, "delta", text.getDelta()));
                                } else if (event instanceof AgentResultEvent reply) {
                                    var message = reply.getResult();
                                    if (message == null || message.getRole() != MsgRole.ASSISTANT) {
                                        throw new BizException(ConversationErrorCode.AGENT_FAILED);
                                    }
                                    GenerateReason reason = message.getGenerateReason();
                                    if (reason != null && reason != GenerateReason.MODEL_STOP && reason != GenerateReason.STRUCTURED_OUTPUT) {
                                        throw new BizException(ConversationErrorCode.AGENT_FAILED);
                                    }
                                    result.set(message.getTextContent());
                                }
                            }).blockLast(Duration.ofMinutes(3));
                    if (terminal.get() || Thread.currentThread().isInterrupted() || result.get() == null || result.get().isBlank()) {
                        throw new BizException(ConversationErrorCode.AGENT_FAILED);
                    }
                    MessageVO reply = conversations.append(userId, id, MessageRole.ASSISTANT,
                            result.get(), gateway.modelName(), traceId);
                    sendEvent(emitter, "complete", Map.of("conversationId", id, "messageId", reply.getId(), "message", reply));
                    terminal.set(true);
                    lease.close();
                    emitter.complete();
                } catch (Exception ex) {
                    log.warn("Agent conversation {} failed ({})", id, ex.getClass().getSimpleName());
                    if (terminal.compareAndSet(false, true)) {
                        try { sendEvent(emitter, "error", Response.fail(ConversationErrorCode.AGENT_FAILED)); }
                        catch (RuntimeException ignored) { /* 已断开的连接无法接收错误事件。 */ }
                        emitter.complete();
                    }
                } finally {
                    worker.set(null);
                }
            };
            Runnable cancel = () -> {
                if (terminal.compareAndSet(false, true)) {
                    Thread running = worker.get();
                    if (running != null) running.interrupt();
                }
            };
            emitter.onTimeout(cancel);
            emitter.onError(error -> cancel.run());
            emitter.onCompletion(cancel);
            executor.execute(work);
            return emitter;
        } catch (RuntimeException ex) {
            lease.close();
            throw ex;
        }
    }

    private static void sendEvent(SseEmitter emitter, String name, Object value) {
        try { emitter.send(SseEmitter.event().name(name).data(value)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}

package cn.lwccxy.trip.agent.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/** 按 AgentScope 会话维护 SSE 连接，并负责连接生命周期登记。 */
@Component
public class SseEmitterRegistry {
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 注册会话当前的 SSE 连接；重复注册时以新连接替换旧连接。 */
    public void register(String sessionId, SseEmitter emitter) {
        if (sessionId == null || sessionId.isBlank() || emitter == null) {
            throw new IllegalArgumentException("sessionId and emitter are required");
        }

        // 先替换索引，使旧连接的完成回调无法移除新连接。
        SseEmitter previous = emitters.put(sessionId, emitter);
        emitter.onCompletion(() -> unregister(sessionId, emitter));
        emitter.onTimeout(() -> unregister(sessionId, emitter));
        emitter.onError(error -> unregister(sessionId, emitter));

        if (previous != null && previous != emitter) {
            try {
                // 结束被替换的连接，释放服务端持有的连接资源。
                previous.complete();
            } catch (IllegalStateException ignored) {
                // 旧连接已经结束时无需影响新连接登记。
            }
        }
    }

    /** 返回会话当前注册的 SSE 连接；会话尚未连接时返回 {@code null}。 */
    public SseEmitter getEmitter(String sessionId) {
        return sessionId == null ? null : emitters.get(sessionId);
    }

    /** 仅当当前连接仍是指定连接时移除会话索引，防止旧连接回调误删新连接。 */
    public void unregister(String sessionId, SseEmitter emitter) {
        if (sessionId != null && emitter != null) {
            emitters.remove(sessionId, emitter);
        }
    }

    /** 移除会话当前 SSE 连接索引，并结束该连接。 */
    public void unregisterAll(String sessionId) {
        if (sessionId == null) {
            return;
        }

        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter != null) {
            emitter.complete();
        }
    }
}

package cn.lwccxy.trip.common.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按用户维护服务端事件推送连接，并负责连接结束后的清理。 */
@Component
@Slf4j
public class SseEmitterRegistry {
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 注册用户的 SSE 连接，并在连接结束、超时或异常时移除该连接。 */
    public void register(Long userId, SseEmitter emitter) {
        if (userId == null || emitter == null) {
            throw new IllegalArgumentException("userId and emitter are required");
        }
        // 每个用户使用并发集合保存多条连接，避免同一用户多端登录时互相覆盖。
        emitters.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> unregister(userId, emitter));
        emitter.onTimeout(() -> unregister(userId, emitter));
        emitter.onError(error -> unregister(userId, emitter));
    }

    /** 返回用户当前登记的 SSE 连接快照；用户没有连接时返回空列表。 */
    public List<SseEmitter> getEmitters(Long userId) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        return userEmitters == null ? List.of() : List.copyOf(userEmitters);
    }

    /** 向用户当前所有连接推送命名事件，并清理已经失效的连接。 */
    public int send(Long userId, String eventName, Object data) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (SseEmitter emitter : userEmitters) {
            try {
                // 单条连接发送失败时只清理该连接，其他设备仍继续接收本次推送。
                emitter.send(SseEmitter.event().name(eventName).data(data));
                sent++;
            } catch (IOException | IllegalStateException ex) {
                log.debug("Removing unavailable SSE connection for user {}", userId);
                unregister(userId, emitter);
            }
        }
        return sent;
    }

    /** 移除指定用户的一条连接，并在集合为空时一并移除用户索引。 */
    public void unregister(Long userId, SseEmitter emitter) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null && userEmitters.remove(emitter) && userEmitters.isEmpty()) {
            emitters.remove(userId, userEmitters);
        }
    }

    /** 移除用户的全部 SSE 连接索引。 */
    public void unregisterAll(Long userId) {
        emitters.remove(userId);
    }
}

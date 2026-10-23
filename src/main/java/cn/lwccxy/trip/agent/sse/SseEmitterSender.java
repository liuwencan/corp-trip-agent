package cn.lwccxy.trip.agent.sse;

import cn.lwccxy.trip.agent.enums.SseEventEnum;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 负责向指定 SSE 连接发送流式事件。 */
public class SseEmitterSender {
    /** 向指定 SSE 连接发送命名事件；传输失败时抛出运行时异常交由流处理终止。 */
    public static void send(SseEmitter emitter, SseEventEnum event, Object data) {
        try {
            // 发送器仅封装 SSE 事件写入，连接的注册和生命周期由其他组件管理。
            emitter.send(SseEmitter.event().name(event.getCode()).data(data));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

package cn.lwccxy.trip.agent.sse;

import cn.lwccxy.trip.agent.enums.SseEventEnum;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** 负责向指定 SSE 连接发送流式事件。 */
public class SseEmitterSender {
    private static final Logger logger = LoggerFactory.getLogger(SseEmitterSender.class);

    /** 向指定 SSE 连接发送命名事件；连接缺失或写入失败时记录日志并跳过发送。 */
    public static void send(SseEmitter emitter, SseEventEnum event, Object data) {
        // 会话连接尚未注册或已被移除时，不尝试写入 SSE 响应。
        if (emitter == null) {
            return;
        }

        try {
            // 发送器仅封装 SSE 事件写入，连接的注册和生命周期由其他组件管理。
            emitter.send(SseEmitter.event().name(event.getCode()).data(data));
        } catch (IOException | IllegalStateException ex) {
            // 客户端断开或连接已结束时只记录传输错误，避免中断 Agent 的业务流。
            logger.warn("SSE 事件发送失败，event={}", event, ex);
        }
    }
}

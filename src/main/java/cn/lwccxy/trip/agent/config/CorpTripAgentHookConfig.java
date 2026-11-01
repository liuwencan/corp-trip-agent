package cn.lwccxy.trip.agent.config;

import cn.lwccxy.trip.agent.hooks.ConfirmationHook;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.hooks.ProgressNotificationHook;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.business.conversation.service.ConversationService;
import io.agentscope.core.session.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Hook 配置。
 */
@Configuration
public class CorpTripAgentHookConfig {

    @Bean
    public PersistSessionHook persistSessionHook(Session mysqlSession) {
        return new PersistSessionHook(mysqlSession);
    }
    /**
     * 创建用于推送 ask_user 确认请求并持久化工具调用记录的 Hook。
     *
     * @param sseEmitterRegistry SSE 连接注册表
     * @param conversationService 会话消息持久化服务
     * @return 确认 Hook 实例
     */
    @Bean
    public ConfirmationHook confirmationHook(SseEmitterRegistry sseEmitterRegistry,
                                             ConversationService conversationService) {
        return new ConfirmationHook(sseEmitterRegistry, conversationService);
    }
    
    @Bean
    public ProgressNotificationHook progressNotificationHook(SseEmitterRegistry sseEmitterRegistry) {
        return new ProgressNotificationHook(sseEmitterRegistry);
    }

}

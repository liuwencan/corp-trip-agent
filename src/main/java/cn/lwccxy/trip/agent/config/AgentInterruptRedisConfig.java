package cn.lwccxy.trip.agent.config;

import cn.lwccxy.trip.agent.AgentRegistry;
import cn.lwccxy.trip.agent.listener.AgentInterruptMessageListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 为跨节点 Agent 中断广播注册 Redis Pub/Sub 监听器。 */
@Configuration
public class AgentInterruptRedisConfig {

    /**
     * 创建并注册会话中断消息监听容器。
     *
     * @param connectionFactory Spring 管理的 Redis 连接工厂
     * @param listener 中断消息接收器
     * @return 订阅 Agent 中断频道的 Redis 消息监听容器
     */
    @Bean
    public RedisMessageListenerContainer agentInterruptMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            AgentInterruptMessageListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 每个应用节点订阅同一频道，再由监听器按本机活跃状态决定是否处理。
        container.addMessageListener(listener, new ChannelTopic(AgentRegistry.INTERRUPT_CHANNEL));
        return container;
    }
}

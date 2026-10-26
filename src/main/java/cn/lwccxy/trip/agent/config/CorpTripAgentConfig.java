package cn.lwccxy.trip.agent.config;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import io.agentscope.core.session.mysql.MysqlSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 差旅智能体所有配置
 */
@Configuration
public class CorpTripAgentConfig {


//    // 注册一个工具执行持久化的扩展点Bean对象, 所有的Agent都用同一个即可
//    @Bean
//    public ToolExecuteStoreMiddleware toolExecuteStoreMiddleware(SseEmitterRegistry sseEmitterRegistry) {
//        return new ToolExecuteStoreMiddleware(sseEmitterRegistry);
//    }




}

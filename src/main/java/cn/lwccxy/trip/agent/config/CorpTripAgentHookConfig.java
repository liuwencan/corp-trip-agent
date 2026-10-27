package cn.lwccxy.trip.agent.config;

import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import io.agentscope.core.session.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Hook 配置。
 *
 * <p>Hook 按每次 prototype Agent 创建时绑定运行时上下文，不注册共享的单例 Hook。</p>
 */
@Configuration
public class CorpTripAgentHookConfig {

    @Bean
    public PersistSessionHook persistSessionHook(Session mysqlSession) {
        return new PersistSessionHook(mysqlSession);
    }

}

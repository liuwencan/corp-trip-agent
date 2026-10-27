package cn.lwccxy.trip.agent.config;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 差旅智能体所有配置
 */
@Configuration
public class CorpTripAgentConfig {


    /**
     * AgentScope MySQL 会话存储。
     * 复用项目已有的 DataSource（Druid），自动建表。
     * 表名默认为 {@code agentscope_session}，用于持久化 Agent 多轮对话历史。
     */
    @Bean
    public Session mysqlSession(DataSource dataSource) {
        return new MysqlSession(dataSource, "corptrip", "agentscope_session", true);
    }




}

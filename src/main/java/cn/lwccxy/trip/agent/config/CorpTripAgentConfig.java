package cn.lwccxy.trip.agent.config;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

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


    @Bean
    public AgentSkillRepository fileSystemSkillRepository(
            @Value("${corp.trip.skillspath:classpath:skills}") String skillPath) throws IOException {
        if (skillPath.startsWith("classpath:")) {
            return new ClasspathSkillRepository(skillPath.substring("classpath:".length()));
        }
        return new FileSystemSkillRepository(Path.of(skillPath));
    }

}

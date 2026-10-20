package cn.lwccxy.trip.agent;


import io.agentscope.core.model.Model;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import javax.sql.DataSource;


@Configuration("masterAgentFactory")
public class MasterAgent {

    private final DataSource dataSource;
    private final Model model;

    public MasterAgent(DataSource dataSource, Model model){
        this.dataSource = dataSource;
        this.model = model;
    }

    // 多例模式：不同用户不能共用同一个Agent, 否则上下文会串, 必须做隔离
    // 这里利用SpringBean的多例模式, 每次获取bean对象的时候都会新建一个实例
    @Bean
    @Scope("prototype")
    public HarnessAgent masterAgent(){
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("MasterAgent")
                .description("智能差旅助手主智能体")
                .model(model) // 模型装配
                .agentId("matser-1")
                .sysPrompt("你是一个主智能体，负责协调各个子智能体")
                .stateStore(new MysqlAgentStateStore(dataSource, true));
        HarnessAgent masterAgent = builder.build();
        return masterAgent;
    }

}

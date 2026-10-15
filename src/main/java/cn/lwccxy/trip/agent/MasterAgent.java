package cn.lwccxy.trip.agent;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Path;

@Component
public class MasterAgent {

    private final DataSource dataSource;


    public MasterAgent(DataSource dataSource){
        this.dataSource = dataSource;
    }

    // 多例模式：不同用户不能共用同一个Agent, 否则上下文会串, 必须做隔离
    // 这里利用SpringBean的多例模式, 每次获取bean对象的时候都会新建一个实例
    @Bean
    @Scope("prototype")
    public HarnessAgent masterAgent(){
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("智能差旅助手主智能体")
                .agentId("matser-1")
                .sysPrompt("你是一个主智能体，负责协调各个子智能体")
                .model("dashscope:qwen-plus")
                .stateStore(new MysqlAgentStateStore(dataSource));
        HarnessAgent masterAgent = builder.build();
        return masterAgent;
    }

}

package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.tools.AskUserTool;
import cn.lwccxy.trip.agent.tools.UserInfoTools;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration("generalInfoAgentConfiguration")
public class GeneralInfoAgent {
    private final DataSource dataSource;
    private final Model model;
    private ReActAgent agent;
//    private final ToolExecuteStoreMiddleware toolExecuteStoreMiddleware;
    private final McpClientWrapper weatherMcpClient;


    /** 创建通用信息子智能体工厂。 */
    public GeneralInfoAgent(DataSource dataSource, Model model, McpClientWrapper weatherMcpClient) {
        this.dataSource = dataSource;
        this.model = model;
        this.weatherMcpClient = weatherMcpClient;
    }



    /** 为每次对话创建隔离的主智能体及其差旅子智能体工具。 */
    @Bean
    @Scope("prototype")
    public ReActAgent generalInfoAgent() {

        // 子智能体按工具调用所需任务独立响应，主智能体负责选择和组合它们的结果。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AskUserTool());
        toolkit.registerTool(new UserInfoTools());

        // 阿里云天气MCP接入
        // https://market.aliyun.com/detail/cmapi00074002?spm=5176.29867242_210807074.0.0.44e83e7eLgrp1C
        toolkit.registerMcpClient(weatherMcpClient).block();
        // 知识库搭建


        return ReActAgent.builder()
                .name("GeneralInfoAgent")
                .description("负责查询天气、目的地资讯、当地交通和餐饮等通用信息")
                .model(model)
                .sysPrompt("你是企业差旅通用信息子智能体。负责查询天气、目的地资讯、当地交通、餐饮和其他出行相关信息。回答时说明信息的时间范围和不确定性，只能使用已注册的查询工具，不得编造实时数据，不负责创建订单或修改差旅单。")
                .toolkit(toolkit)
                .maxIters(5) // 最大循环轮数
                .toolExecutionConfig(ExecutionConfig.builder() // 工具执行配置
                        .timeout(Duration.ofMinutes(1))
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofSeconds(3))
                        .retryOn(error -> error instanceof java.io.IOException)
                        .build())
                .ragMode(RAGMode.AGENTIC)
//                .middleware(toolExecuteStoreMiddleware)
//                .stateStore(new MysqlAgentStateStore(dataSource, "corptrip", "agentscope_sessions", true)) // 上下文持久化组件
                .build();
    }
}

package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.hooks.ProgressNotificationHook;
import cn.lwccxy.trip.agent.tools.AskUserTool;
import cn.lwccxy.trip.agent.tools.UserInfoTools;
import cn.lwccxy.trip.agent.tools.DestinationLiveTools;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Configuration("generalInfoAgentConfiguration")
public class GeneralInfoAgent {
    private final Logger logger = LoggerFactory.getLogger(GeneralInfoAgent.class);

    private final DataSource dataSource;
    private final Model model;
    private ReActAgent agent;
//    private final ToolExecuteStoreMiddleware toolExecuteStoreMiddleware;
    private final McpClientWrapper weatherMcpClient;
    private final Knowledge corptripGuidelinesKnowledge;
    private final Knowledge corptripPolicyKnowledge;
    private final PersistSessionHook persistSessionHook;
    private final ProgressNotificationHook progressNotificationHook;
    private final DestinationLiveTools destinationLiveTools;




    /**
     * 创建通用出行信息 Agent 配置，并绑定出差指南知识库。
     *
     * @param dataSource 数据源
     * @param model 对话模型
     * @param weatherMcpClient 天气 MCP 客户端
     */
    public GeneralInfoAgent(DataSource dataSource, Model model, McpClientWrapper weatherMcpClient,
                            @Qualifier("corptripGuidelinesKnowledge") Knowledge corptripGuidelinesKnowledge, @Qualifier("corptripPolicyKnowledge") Knowledge corptripPolicyKnowledge,
                            PersistSessionHook persistSessionHook, ProgressNotificationHook progressNotificationHook,
                            DestinationLiveTools destinationLiveTools) {
        this.dataSource = dataSource;
        this.model = model;
        this.weatherMcpClient = weatherMcpClient;
        this.corptripGuidelinesKnowledge = corptripGuidelinesKnowledge;
        this.corptripPolicyKnowledge = corptripPolicyKnowledge;
        this.persistSessionHook = persistSessionHook;
        this.progressNotificationHook = progressNotificationHook;
        this.destinationLiveTools = destinationLiveTools;
    }



    /** 为每次对话创建隔离的主智能体及其差旅子智能体工具。 */
    @Bean
    @Scope("prototype")
    public ReActAgent generalInfoAgent() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();
        logger.info("[GeneralInfoAgent]正在构建中, userId: {}, sessionId: {}", agentRuntimeContext.getUserId(), agentRuntimeContext.getSessionId());

        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(agentRuntimeContext)
                .build();

        // 子智能体按工具调用所需任务独立响应，主智能体负责选择和组合它们的结果。
        Toolkit toolkit = new Toolkit();
//        toolkit.registerTool(new AskUserTool());
        toolkit.registerTool(new UserInfoTools());
        // 通用信息查询与行程规划共享同一套目的地天气和最新资讯工具。
        toolkit.registerTool(destinationLiveTools);

        // 阿里云天气MCP接入
        // https://market.aliyun.com/detail/cmapi00074002?spm=5176.29867242_210807074.0.0.44e83e7eLgrp1C
        toolkit.registerMcpClient(weatherMcpClient).block();

        // AgentScope的六层上下文压缩机制
        AutoContextConfig autoContextConfig = AutoContextConfig.builder()
                .build();

        return ReActAgent.builder()
                .name("GeneralInfoAgent")
                .description("负责查询天气、目的地资讯、当地交通和餐饮等通用信息")
                .model(model)
                .sysPrompt("你是企业差旅通用信息子智能体。负责查询天气、目的地资讯、当地交通、餐饮和其他出行相关信息。涉及天气时优先调用 query_weather，涉及目的地最新活动、交通或风险资讯时调用 query_destination_news；工具未启用或查询失败时明确说明。回答时说明来源、时间范围和不确定性，只能使用已注册的查询工具，不得编造实时数据，不负责创建订单或修改差旅单。")
                .toolkit(toolkit)
                .maxIters(5) // 最大循环轮数
                .toolExecutionConfig(ExecutionConfig.builder() // 工具执行配置
                        .timeout(Duration.ofMinutes(1))
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofSeconds(3))
                        .retryOn(error -> error instanceof IOException)
                        .build())
                .toolExecutionContext(context)
                // 启用 Generic RAG 模式
                .knowledge(corptripGuidelinesKnowledge).knowledge(corptripPolicyKnowledge) // 添加 差旅政策/差旅指南 知识库d
                .ragMode(RAGMode.AGENTIC)
                .memory(new AutoContextMemory(autoContextConfig, model))
                .hooks(List.of(persistSessionHook, progressNotificationHook))
                .build();
    }
}

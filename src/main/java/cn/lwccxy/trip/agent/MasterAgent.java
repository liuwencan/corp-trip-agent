package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.ConfirmationHook;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.tools.AskUserTool;
import cn.lwccxy.trip.agent.tools.UserInfoTools;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 配置主智能体，并将差旅领域子智能体注册为可调用工具。 */
@Configuration("masterAgentConfiguration")
public class MasterAgent {

    private final Logger logger = LoggerFactory.getLogger(MasterAgent.class);

    private final SseEmitterRegistry sseEmitterRegistry;
    private final DataSource dataSource;
    private final Model model;
    private final ApplicationContext applicationContext;
    private final PersistSessionHook persistSessionHook;
    private final McpClientWrapper weatherMcpClient;
    private final ConfirmationHook confirmationHook;

    /** 创建主智能体配置。 */
    public MasterAgent(DataSource dataSource, Model model, ApplicationContext applicationContext,
                       SseEmitterRegistry sseEmitterRegistry, PersistSessionHook persistSessionHook, McpClientWrapper weatherMcpClient
    , ConfirmationHook confirmationHook) {
        this.dataSource = dataSource;
        this.model = model;
        this.applicationContext = applicationContext;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.persistSessionHook = persistSessionHook;
        this.weatherMcpClient = weatherMcpClient;
        this.confirmationHook = confirmationHook;
    }

    /** 为每次对话创建隔离的主智能体及其差旅子智能体工具。 */
    @Bean
    @Scope("prototype")
    public ReActAgent masterAgent() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();
        logger.info("MasterAgent正在构建中, userId: {}, sessionId: {}", agentRuntimeContext.getUserId(), agentRuntimeContext.getSessionId());

        // 子智能体按工具调用所需任务独立响应，主智能体负责选择和组合它们的结果。
        Toolkit toolkit = new Toolkit();

        // 每次Build主智能体的时候都要重Spring上下文中取出对应的子Agent, 这样才能做多租户隔离
        ReActAgent generalInfoAgent = applicationContext.getBean("generalInfoAgent", ReActAgent.class);

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("GeneralInfoAgent")                    // 自定义工具名称
                .description("负责查询天气、目的地资讯、用户信息、当地交通和餐饮等通用信息")              // 自定义描述
                .forwardEvents(false)                       // 不转发子智能体事件, 子智能体内部执行产生的事情对主智能体的HOOK不可见
                .build();

        toolkit.registerAgentTool(
                new SubAgentTool(() -> generalInfoAgent, config)
        );
        toolkit.registerTool(new AskUserTool());
        // 阿里云天气MCP接入
        // https://market.aliyun.com/detail/cmapi00074002?spm=5176.29867242_210807074.0.0.44e83e7eLgrp1C
//        toolkit.registerMcpClient(weatherMcpClient).block();

        // 工具执行上下文, 后续可以在该Agent的多个Hook机制中去取出这些参数
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(agentRuntimeContext)
                .build();

        // AgentScope的六层上下文压缩机制
        AutoContextConfig autoContextConfig = AutoContextConfig.builder()
                .build();

        // 每次创建 prototype Agent 都绑定独立 Hook，避免并发会话共享运行时上下文。
        ReActAgent masterAgent = ReActAgent.builder()
                .name("MasterAgent")
                .description("负责理解企业差旅请求并协调差旅单、通用信息和行程规划子智能体")
                .model(model)
                .sysPrompt("你是企业智能差旅助手主智能体。 负责子Agent的调度和编排，目前有infoAgent，主要是查询通用信息的")
                .toolkit(toolkit)
                .toolExecutionContext(context)
                .hooks(List.of(new AutoContextHook(), confirmationHook, persistSessionHook))
                .memory(new AutoContextMemory(autoContextConfig, model)) // AgentScope的六层上下文压缩机制
                .build();

        logger.info("MasterAgent 构建成功");
        return masterAgent;
    }
}

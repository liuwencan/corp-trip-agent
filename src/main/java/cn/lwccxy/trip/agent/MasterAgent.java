package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.ConfirmationHook;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.hooks.ProgressNotificationHook;
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
    private final ProgressNotificationHook progressNotificationHook;

    /** 创建主智能体配置。 */
    public MasterAgent(DataSource dataSource, Model model, ApplicationContext applicationContext,
                       SseEmitterRegistry sseEmitterRegistry, PersistSessionHook persistSessionHook,
                       McpClientWrapper weatherMcpClient, ConfirmationHook confirmationHook, ProgressNotificationHook progressNotificationHook) {
        this.dataSource = dataSource;
        this.model = model;
        this.applicationContext = applicationContext;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.persistSessionHook = persistSessionHook;
        this.weatherMcpClient = weatherMcpClient;
        this.confirmationHook = confirmationHook;
        this.progressNotificationHook = progressNotificationHook;
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
        // 行程单管理 Agent 同样按本次会话创建，避免不同会话共享运行时状态。
        ReActAgent tripOrderAgent = applicationContext.getBean("orderManagementAgent", ReActAgent.class);

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("GeneralInfoAgent")                    // 自定义工具名称
                .description("负责查询天气、目的地资讯、用户信息、当地交通和餐饮等通用信息")              // 自定义描述
                .forwardEvents(false)                       // 不转发子智能体事件, 子智能体内部执行产生的事情对主智能体的HOOK不可见
                .build();

        toolkit.registerAgentTool(
                new SubAgentTool(() -> generalInfoAgent, config)
        );
        SubAgentConfig tripOrderConfig = SubAgentConfig.builder()
                .toolName("OrderManagementAgent")
                .description("负责创建并提交出差申请到管理员待审批队列、查询申请单和审批记录，并生成已有申请单的修改或取消草案")
                .forwardEvents(false)
                .build();
        toolkit.registerAgentTool(new SubAgentTool(() -> tripOrderAgent, tripOrderConfig));
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
                .sysPrompt("你是企业智能差旅助手主智能体，负责理解请求并协调专业子智能体。"
                        + "天气、目的地、交通、餐饮、用户信息和差旅政策问题交给 GeneralInfoAgent；"
                        + "创建出差申请并提交管理员待审批队列、已有申请单和审批记录查询、申请单修改或取消草案交给 TripOrderManagementAgent。"
                        + "行程规划和预订请求当前没有对应的业务执行智能体，不得声称已规划落单或完成预订；"
                        + "申请工具返回 SUBMITTED 和 PENDING 表示已进入管理员审批队列，不表示审批通过。多项独立诉求应逐项处理。")
                .toolkit(toolkit)
                .toolExecutionContext(context)
                .hooks(List.of(new AutoContextHook(), confirmationHook, persistSessionHook, progressNotificationHook))
                .memory(new AutoContextMemory(autoContextConfig, model)) // AgentScope的六层上下文压缩机制
                .build();

        logger.info("MasterAgent 构建成功");
        return masterAgent;
    }
}

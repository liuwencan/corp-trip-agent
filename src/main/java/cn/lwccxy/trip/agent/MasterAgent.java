package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.middleware.HumanInTheLoopMiddleware;
import cn.lwccxy.trip.agent.middleware.ToolExecuteStoreMiddleware;
import cn.lwccxy.trip.agent.tools.AskUserTool;
import cn.lwccxy.trip.agent.tools.UserInfoTools;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.model.Model;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentProvider;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.modelcontextprotocol.spec.McpSchema;
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
    private final DataSource dataSource;
    private final Model model;
    private final TripRequestAgent tripRequestAgent;
    private final ItineraryPlanningAgent itineraryPlanningAgent;
    private final ToolExecuteStoreMiddleware toolExecuteStoreMiddleware;
    private final ApplicationContext applicationContext;

    /** 创建主智能体配置。 */
    public MasterAgent(DataSource dataSource, Model model, TripRequestAgent tripRequestAgent,
                       ItineraryPlanningAgent itineraryPlanningAgent, ToolExecuteStoreMiddleware toolExecuteStoreMiddleware, ApplicationContext applicationContext) {
        this.dataSource = dataSource;
        this.model = model;
        this.tripRequestAgent = tripRequestAgent;
        this.itineraryPlanningAgent = itineraryPlanningAgent;
        this.toolExecuteStoreMiddleware = toolExecuteStoreMiddleware;
        this.applicationContext = applicationContext;
    }

    /** 为每次对话创建隔离的主智能体及其差旅子智能体工具。 */
    @Bean
    @Scope("prototype")
    public ReActAgent masterAgent() {

        // 子智能体按工具调用所需任务独立响应，主智能体负责选择和组合它们的结果。
        Toolkit toolkit = new Toolkit();

        // 每次Build主智能体的时候都要重Spring上下文中取出对应的子Agent, 这样才能做多租户隔离
        ReActAgent generalInfoAgent = applicationContext.getBean("generalInfoAgent", ReActAgent.class);

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName("GeneralInfoAgent")                    // 自定义工具名称
                .description("负责查询天气、目的地资讯、用户信息、当地交通和餐饮等通用信息")              // 自定义描述
                .forwardEvents(true)                       // 转发子智能体事件
                .build();
        toolkit.registerAgentTool(new SubAgentTool((SubAgentProvider<ReActAgent>) generalInfoAgent, config));

        toolkit.registerTool(new AskUserTool());


        return ReActAgent.builder()
                .name("MasterAgent")
                .description("负责理解企业差旅请求并协调差旅单、通用信息和行程规划子智能体")
                .model(model)
                .sysPrompt("你是企业智能差旅助手主智能体。 负责子Agent的调度和编排，如果要查询用户信息,就调用infoagent即可")
                .toolkit(toolkit)
                .middleware(toolExecuteStoreMiddleware)
                .stateStore(new MysqlAgentStateStore(dataSource, "corptrip", "agentscope_sessions", true)) // 上下文持久化组件
                .build();
    }
}

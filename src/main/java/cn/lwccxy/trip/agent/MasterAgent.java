package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.middleware.HumanInTheLoopMiddleware;
import cn.lwccxy.trip.agent.tools.AskUserTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/** 配置主智能体，并将差旅领域子智能体注册为可调用工具。 */
@Configuration("masterAgentFactory")
public class MasterAgent {
    private final DataSource dataSource;
    private final Model model;
    private final TripRequestAgent tripRequestAgent;
    private final GeneralInfoAgent generalInfoAgent;
    private final ItineraryPlanningAgent itineraryPlanningAgent;

    /** 创建主智能体配置。 */
    public MasterAgent(DataSource dataSource, Model model, TripRequestAgent tripRequestAgent,
                       GeneralInfoAgent generalInfoAgent, ItineraryPlanningAgent itineraryPlanningAgent) {
        this.dataSource = dataSource;
        this.model = model;
        this.tripRequestAgent = tripRequestAgent;
        this.generalInfoAgent = generalInfoAgent;
        this.itineraryPlanningAgent = itineraryPlanningAgent;
    }

    /** 为每次对话创建隔离的主智能体及其差旅子智能体工具。 */
    @Bean
    @Scope("prototype")
    public ReActAgent masterAgent() {
        ReActAgent tripAgent = tripRequestAgent.getAgent();
        ReActAgent informationAgent = generalInfoAgent.getAgent();
        ReActAgent planningAgent = itineraryPlanningAgent.getAgent();

        // 子智能体按工具调用所需任务独立响应，主智能体负责选择和组合它们的结果。
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SubAgentTool(() -> tripAgent,
                SubAgentConfig.builder()
                        .toolName("manage_trip_request")
                        .description("创建、查询、更新差旅单，提交审批或查询审批进度")
                        .build()));
        toolkit.registerAgentTool(new SubAgentTool(() -> informationAgent,
                SubAgentConfig.builder()
                        .toolName("query_travel_information")
                        .description("查询天气、目的地资讯、当地交通和餐饮等差旅信息")
                        .build()));
        toolkit.registerAgentTool(new SubAgentTool(() -> planningAgent,
                SubAgentConfig.builder()
                        .toolName("plan_itinerary")
                        .description("根据差旅条件规划路线、交通、住宿和饮食，并比较候选方案")
                        .build()));

        toolkit.registerTool(new AskUserTool());


        return ReActAgent.builder()
                .name("MasterAgent")
                .description("负责理解企业差旅请求并协调差旅单、通用信息和行程规划子智能体")
                .model(model)
                .sysPrompt("你是企业智能差旅助手主智能体。根据用户请求选择并调用差旅单管理、通用信息和行程规划工具。涉及差旅单状态变更时先确认用户意图并使用差旅单工具；审批结果只能来自业务工具，不能自行判定。行程规划需提供可比较方案并让用户决策，不得将方案选择或预订视为用户已确认。对于工具尚未支持的操作，明确告知用户，不得虚构已完成的查询、审批或预订。如果不确定用户的意图，可以调用ask_user工具")
                .toolkit(toolkit)
                .middleware(new HumanInTheLoopMiddleware())
                .stateStore(new MysqlAgentStateStore(dataSource, "corptrip", "agentscope_sessions", true)) // 上下文持久化组件
                .build();
    }
}

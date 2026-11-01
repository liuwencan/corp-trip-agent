package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.hooks.ProgressNotificationHook;
import cn.lwccxy.trip.agent.tools.DestinationLiveTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;
import java.util.Set;

/** 配置只生成差旅行程候选方案的规划子智能体。 */
@Configuration("itineraryPlanningAgentConfiguration")
public class ItineraryPlanningAgent {

    private final Model model;
    private final PersistSessionHook persistSessionHook;
    private final ProgressNotificationHook progressNotificationHook;
    private final McpClientWrapper weatherMcpClient;
    private final DestinationLiveTools destinationLiveTools;
    private final AgentSkillRepository agentSkillRepository;


    /** 创建行程规划智能体配置。 */
    public ItineraryPlanningAgent(Model model, PersistSessionHook persistSessionHook,
                                  ProgressNotificationHook progressNotificationHook, McpClientWrapper weatherMcpClient,
                                  DestinationLiveTools destinationLiveTools, AgentSkillRepository agentSkillRepository) {
        this.model = model;
        this.persistSessionHook = persistSessionHook;
        this.progressNotificationHook = progressNotificationHook;
        this.weatherMcpClient = weatherMcpClient;
        this.destinationLiveTools = destinationLiveTools;
        this.agentSkillRepository = agentSkillRepository;
    }

    /** 为当前业务会话创建隔离的行程规划智能体。 */
    @Bean
    @Scope("prototype")
    public ReActAgent itineraryPlanningAgent() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();

        // 绑定本次会话上下文，使智能体 Hook 按当前用户和会话处理进度及持久化。
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(agentRuntimeContext)
                .build();
        AutoContextConfig autoContextConfig = AutoContextConfig.builder().build();

        Toolkit toolkit = new Toolkit();
        // 提供wttr.in短期天气和NewsData.io目的地资讯查询，补足规划所需的实时参考信息。
        toolkit.registerTool(destinationLiveTools);
        // 将天气 MCP 直接提供给规划智能体，使其能结合具体行程日期核对天气并调整建议。
        toolkit.registerMcpClient(weatherMcpClient).block();


        // 沙箱
        SkillBox skillBox = buildPlanningSkillBox(toolkit);


        // 规划智能体只组合上游目的地咨询和天气结果，不虚构未接入的交通、酒店或价格数据。
        return ReActAgent.builder()
                .name("ItineraryPlanningAgent")
                .description("结合目的地咨询和天气信息，根据出差目标与约束生成交通、住宿及日程候选方案，并说明取舍、假设和信息缺口")
                .model(model)
                .sysPrompt("你是企业差旅行程规划子智能体，只负责根据用户明确提供的条件生成一个或多个候选方案。"
                        + "规划前识别出发地、目的地、出发和返回日期、出差目的、人数、预算及偏好；缺少会显著影响方案的条件时，先列出待补充信息，不得自行猜测。"
                        + "先阅读主智能体提供的目的地咨询和天气查询结果；缺少适用的目的地资讯或天气信息时，调用 query_destination_news 和 query_weather 补充查询。按目的地特点、行程日期天气及差旅目的调整每日安排，并给出穿着、交通衔接、室内外活动取舍等实用建议。天气结果缺失或超出可查询范围时，明确说明未能核实，不得推测天气。"
                        + "按日期安排交通、住宿和日程，解释路线与时间取舍，并区分已查询事实、目的地常识建议和规划假设。目的地实时信息应以工具查询结果为准，不能把模型常识描述成实时查询。"
                        + "当前没有交通、酒店、库存或价格查询工具；不得声称查询过实时班次、房态、政策或价格，不得编造供应商、报价、可订状态或来源。"
                        + "如需预算描述，只能明确标记为未经查询的粗略估算或留待查询，不可当作报价或政策校验结果。"
                        + "只输出规划建议，不创建申请、预订或修改订单，不占用预算，也不声称已通过政策审核或已完成预订。")
                .toolkit(toolkit)
                .skillBox(skillBox)
                .toolExecutionContext(context)
                .memory(new AutoContextMemory(autoContextConfig, model))
                .hooks(List.of(persistSessionHook, progressNotificationHook))
                .build();
    }


    private SkillBox buildPlanningSkillBox(Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);
        // 把 resource/skills/tuniu-cli skill注册给Agent
        skillBox.registerSkill(agentSkillRepository.getSkill("tuniu-cli"));
//        skillBox.registerSkill(agentSkillRepository.getSkill("html-plan"));
        // 注册 Shell 工具给Agent
        ShellCommandTool shellTool = new ShellCommandTool(
                null,
                Set.of("curl", "npm", "npx", "tuniu", "rgh", "nohup", "sleep", "cat", "grep", "node", "date", "env", "which", "bash", "ls", "mkdir", "source", "echo"),
                null);
        skillBox.codeExecution().withShell(shellTool).withRead().withWrite().enable();
        return skillBox;
    }
}

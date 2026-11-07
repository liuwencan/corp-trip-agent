package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.PersistSessionHook;
import cn.lwccxy.trip.agent.hooks.ModelUsageBillingHook;
import cn.lwccxy.trip.agent.tools.TripOrderReadTools;
import cn.lwccxy.trip.agent.tools.TripOrderWriteTools;
import cn.lwccxy.trip.agent.tools.TripScheduleConflictTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

/** 配置负责查询和管理既有差旅行程单的专职智能体。 */
@Configuration("orderManagementAgentConfiguration")
public class OrderManagementAgent {

    private final Model model;
    private final PersistSessionHook persistSessionHook;
    private final ModelUsageBillingHook modelUsageBillingHook;
    private final TripOrderReadTools tripOrderReadTools;
    private final TripOrderWriteTools tripOrderWriteTools;
    private final TripScheduleConflictTools tripScheduleConflictTools;

    /** 创建行程单管理智能体配置。 */
    public OrderManagementAgent(Model model, PersistSessionHook persistSessionHook,
                                TripOrderReadTools tripOrderReadTools,
                                TripOrderWriteTools tripOrderWriteTools,
                                TripScheduleConflictTools tripScheduleConflictTools,
                                ModelUsageBillingHook modelUsageBillingHook) {
        this.model = model;
        this.persistSessionHook = persistSessionHook;
        this.modelUsageBillingHook = modelUsageBillingHook;
        this.tripOrderReadTools = tripOrderReadTools;
        this.tripOrderWriteTools = tripOrderWriteTools;
        this.tripScheduleConflictTools = tripScheduleConflictTools;
    }

    /** 为当前业务会话创建独立的行程单管理智能体。 */
    @Bean
    @Scope("prototype")
    public ReActAgent orderManagementAgent() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();

        // 将当前请求上下文绑定到新建 Agent，供 Hook 按会话和智能体名称隔离持久化状态。
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(agentRuntimeContext)
                .build();
        AutoContextConfig autoContextConfig = AutoContextConfig.builder().build();
        Toolkit toolkit = new Toolkit();
        // 工具内部从执行上下文取得当前用户，避免模型通过参数扩大数据访问范围。
        // 读写工具分开注册，便于按风险控制工具集合和后续接入确认、审计流程。
        toolkit.registerTool(tripOrderReadTools);
        toolkit.registerTool(tripOrderWriteTools);
        toolkit.registerTool(tripScheduleConflictTools);

        return ReActAgent.builder()
                .name("TripOrderManagementAgent")
                .description("负责创建出差申请并进入管理员待审批队列、查询当前用户的申请单和审批记录，并生成受控变更草案")
                .model(model)
                .sysPrompt("你是企业智能差旅行程单管理助手，负责为当前用户创建出差申请并提交管理员待审批队列、查询申请单和审批记录，并整理修改或取消需求。"
                        + "查询必须使用已注册工具，不能直接访问数据库，也不能接受用户传入的 userId 扩大查询范围。"
                        + "申请单状态不等于机票、火车票或酒店已预订成功；申请单和审批记录状态不一致时分别展示并说明需要核对。"
                        + "修改或取消只能先生成变更草案，向用户展示当前状态、操作和影响并等待明确确认；当前版本不直接执行供应商订单变更。"
                        + "创建申请时必须收集目的地、出发城市、出发日期、返回日期和出差事由；每次创建前必须调用 check_trip_schedule_conflicts 并传入出发城市、目的地及日期。"
                        + "如果检测到冲突，必须根据 type 和 severity 逐条向用户解释同城重复、跨城日期重叠、同日跨城衔接或相邻日路径断裂的具体原因，并展示已有申请、风险和建议，等待用户明确选择继续或修改时间；只有用户明确选择继续后才可将 confirmConflict=true 调用创建工具。"
                        + "工具成功返回 SUBMITTED/PENDING 表示已进入管理员审批队列，不代表审批通过。"
                        + "工具没有返回的数据、空结果、异常或未知状态都必须如实说明，不得猜测或声称操作成功。")
                .toolkit(toolkit)
                .toolExecutionContext(context)
                .memory(new AutoContextMemory(autoContextConfig, model))
                .hooks(List.of(persistSessionHook, modelUsageBillingHook))
                .build();
    }
}

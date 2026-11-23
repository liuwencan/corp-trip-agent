package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.*;
import cn.lwccxy.trip.agent.memory.CropTripLongTermMemory;
import cn.lwccxy.trip.agent.store.plan.RedisPlanStorage;
import cn.lwccxy.trip.agent.tools.*;
import cn.lwccxy.trip.common.utils.RedisCacheUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置只生成差旅行程候选方案的规划子智能体。 */
@Configuration("itineraryPlanningAgentConfiguration")
public class ItineraryPlanningAgent {

    private static final String PROMPT_RESOURCE_PATH = "sysprompt/ItineraryPlanningAgentPrompt.md";
    private final Logger logger = LoggerFactory.getLogger(ItineraryPlanningAgent.class);

    private final Model model;
    private final PersistSessionHook persistSessionHook;
    private final PersistPlanNoteHook persistPlanNoteHook;
    private final ProgressNotificationHook progressNotificationHook;
    private final ModelUsageBillingHook modelUsageBillingHook;
    private final AgentExecutionMonitorHook agentExecutionMonitorHook;
    private final AgentTracePersistenceHook agentTracePersistenceHook;
    private final AgentRegistryHook agentRegistryHook;
    private final LongTermMemoryRecordHook longTermMemoryRecordHook;
    private final TuniuApiKeyShellHook tuniuApiKeyShellHook;
    private final TuniuSearchResultHook tuniuSearchResultHook;
    private final TuniuSearchResultCaptureHook tuniuSearchResultCaptureHook;
    private final SkillContentCollapseHook skillContentCollapseHook;
    private final McpClientWrapper weatherMcpClient;
    private final List<String> enabledWeatherTools;
    private final DestinationLiveTools destinationLiveTools;
    private final ProviderApiKeyTools providerApiKeyTools;
    private final AgentSkillRepository agentSkillRepository;
    private final String itineraryPlanningPrompt;
    private final String dashscopeApiKey;
    private final String memoryLibraryId;
    private final TripOrderReadTools tripOrderReadTools;
    private final ItineraryPlanDesignTools itineraryPlanDesignTools;
    private final ItineraryPlanReadTools itineraryPlanReadTools;
    private final ItineraryReviewTools itineraryReviewTools;
    private final PolicyTools policyTools;
    private final RedisCacheUtil redisCache;
    private final StringRedisTemplate redisTemplate;
    private final UserProfileReadTools userProfileReadTools;
    private final UserProfileWriteTools userProfileWriteTools;
    private final ActiveAgentPersistenceHook activeAgentPersistenceHook;
    private final ToolCircuitBreakerHook toolCircuitBreakerHook;
    public static final String TOOL_CIRCUIT_BREAKER_GROUP = "tool_circuit_breaker_group";

    /**
     * 创建行程规划智能体配置。
     *
     * @param enabledWeatherTools 配置允许注册的天气 MCP 工具名称
     * @param dashscopeApiKey 百炼 API Key
     * @param memoryLibraryId 百炼长期记忆库标识
     */
    public ItineraryPlanningAgent(Model model, PersistSessionHook persistSessionHook,
                                  PersistPlanNoteHook persistPlanNoteHook,
                                  ProgressNotificationHook progressNotificationHook, McpClientWrapper weatherMcpClient,
                                  @Value("${corp.trip.weather-mcp.enabled-tools:}") List<String> enabledWeatherTools,
                                  DestinationLiveTools destinationLiveTools, AgentSkillRepository agentSkillRepository,
                                  ProviderApiKeyTools providerApiKeyTools,
                                  ModelUsageBillingHook modelUsageBillingHook,
                                  AgentExecutionMonitorHook agentExecutionMonitorHook,
                                  AgentTracePersistenceHook agentTracePersistenceHook,
                                  AgentRegistryHook agentRegistryHook,
                                  LongTermMemoryRecordHook longTermMemoryRecordHook,
                                  TuniuApiKeyShellHook tuniuApiKeyShellHook,
                                  TuniuSearchResultHook tuniuSearchResultHook,
                                  TuniuSearchResultCaptureHook tuniuSearchResultCaptureHook,
                                  SkillContentCollapseHook skillContentCollapseHook,
                                  @Value("${corp.trip.bailian.dashscope-api}") String dashscopeApiKey,
                                  @Value("${corp.trip.bailian.memory-library-id}") String memoryLibraryId,
                                  TripOrderReadTools tripOrderReadTools,
                                  ItineraryPlanDesignTools itineraryPlanDesignTools,
                                  ItineraryPlanReadTools itineraryPlanReadTools,
                                  ItineraryReviewTools itineraryReviewTools,
                                  PolicyTools policyTools,
                                  RedisCacheUtil redisCache,
                                  StringRedisTemplate redisTemplate,
                                  UserProfileReadTools userProfileReadTools,
                                  UserProfileWriteTools userProfileWriteTools,
                                  ActiveAgentPersistenceHook activeAgentPersistenceHook,
                                  ToolCircuitBreakerHook toolCircuitBreakerHook) {
        this.model = model;
        this.persistSessionHook = persistSessionHook;
        this.persistPlanNoteHook = persistPlanNoteHook;
        this.progressNotificationHook = progressNotificationHook;
        this.modelUsageBillingHook = modelUsageBillingHook;
        this.agentExecutionMonitorHook = agentExecutionMonitorHook;
        this.agentTracePersistenceHook = agentTracePersistenceHook;
        this.agentRegistryHook = agentRegistryHook;
        this.longTermMemoryRecordHook = longTermMemoryRecordHook;
        this.tuniuApiKeyShellHook = tuniuApiKeyShellHook;
        this.tuniuSearchResultHook = tuniuSearchResultHook;
        this.tuniuSearchResultCaptureHook = tuniuSearchResultCaptureHook;
        this.skillContentCollapseHook = skillContentCollapseHook;
        this.weatherMcpClient = weatherMcpClient;
        // 清理配置中的空格和空项，防止它们影响 MCP 工具名精确匹配。
        this.enabledWeatherTools = enabledWeatherTools == null ? List.of() : enabledWeatherTools.stream()
                .filter(toolName -> toolName != null && !toolName.isBlank())
                .map(String::trim)
                .toList();
        this.destinationLiveTools = destinationLiveTools;
        this.providerApiKeyTools = providerApiKeyTools;
        this.agentSkillRepository = agentSkillRepository;
        this.itineraryPlanningPrompt = loadPrompt();
        this.dashscopeApiKey = dashscopeApiKey;
        this.memoryLibraryId = memoryLibraryId;
        this.tripOrderReadTools = tripOrderReadTools;
        this.itineraryPlanDesignTools = itineraryPlanDesignTools;
        this.itineraryPlanReadTools = itineraryPlanReadTools;
        this.itineraryReviewTools = itineraryReviewTools;
        this.policyTools = policyTools;
        this.redisCache = redisCache;
        this.redisTemplate = redisTemplate;
        this.userProfileReadTools = userProfileReadTools;
        this.userProfileWriteTools = userProfileWriteTools;
        this.activeAgentPersistenceHook = activeAgentPersistenceHook;
        this.toolCircuitBreakerHook = toolCircuitBreakerHook;
    }

    /** 为当前业务会话创建隔离的行程规划智能体。 */
    @Bean
    @Scope("prototype")
    public ReActAgent itineraryPlanningAgent() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();
        logger.info("[ItineraryPlanningAgent] 行程规划智能体正在构建中, userId: {}, sessionId: {}", agentRuntimeContext.getUserId(), agentRuntimeContext.getSessionId());

        // 绑定本次会话上下文，使智能体 Hook 按当前用户和会话处理进度及持久化。
        ToolExecutionContext context = ToolExecutionContext.builder()
                .register(agentRuntimeContext)
                .build();
        AutoContextConfig autoContextConfig = AutoContextConfig.builder()
                .largePayloadThreshold(4096) // 大消息阈值设定为4096个字符
                .lastKeep(10) // 保护区
                .msgThreshold(50) // 当上下文的消息列表数量超过50条
                .maxToken(150000) // 压缩触发阈值 = maxToken * tokenRatio  （ps：maxToken通常配置为使用模型的上下文窗口大小）
                .tokenRatio(0.75)
                .minConsecutiveToolMessages(6) // 用于L1级压缩: 寻找工具组区间内连续调用工具次数超过6个，才对这个区间调用LLM总结摘要
                .currentRoundCompressionRatio(0.5) // 针对L6级压缩: 决定利用LLM摘要后的结果占没摘要前的原文的 x%, 配置越小的话, 压缩后损失的信息会越多
                .build();


        Toolkit toolkit = new Toolkit();
        // 创建工具分组
        toolkit.createToolGroup(TOOL_CIRCUIT_BREAKER_GROUP, TOOL_CIRCUIT_BREAKER_GROUP, true);

        // 提供wttr.in短期天气和NewsData.io目的地资讯查询，补足规划所需的实时参考信息。
        toolkit.registration().tool(destinationLiveTools).group(TOOL_CIRCUIT_BREAKER_GROUP).apply(); // 把资讯查询工具应用到分组里面
        toolkit.registerTool(providerApiKeyTools);
        toolkit.registerTool(tripOrderReadTools); // 出差行程单读取工具
        toolkit.registerTool(itineraryPlanDesignTools); // 行程方案设计入口
        toolkit.registerTool(itineraryPlanReadTools); // 读取途牛搜索结果并转换为规划候选
        toolkit.registerTool(itineraryReviewTools); // 审核已生成的代表方案
        toolkit.registerTool(policyTools); // 差旅政策查询与校验
        toolkit.registerTool(userProfileReadTools); // 用户资料读工具(预订下单需要)
        toolkit.registerTool(userProfileWriteTools); // 用户资料写工具（用户在对话窗口内补充信息需要）
        // 只注册 YAML 白名单中的天气 MCP 工具；空名单时跳过注册，避免空名单退化为全量开放。
        if (weatherMcpClient != null && !enabledWeatherTools.isEmpty()) {
            toolkit.registration().mcpClient(weatherMcpClient).enableTools(enabledWeatherTools);
        } else {
            logger.warn("[ItineraryPlanningAgent]天气 MCP 客户端未注册，客户端缺失或 corp.trip.weather-mcp.enabled-tools 未配置");
        }
        // 沙箱
        SkillBox skillBox = buildPlanningSkillBox(toolkit);
        // 长期记忆
        LongTermMemory longTermMemory = createLongTermMemory();
        // ====================== 智能体Plan模式 ==========================
        String userId = agentRuntimeContext.getUserId();
        RedisPlanStorage redisPlanStorage = new RedisPlanStorage(redisTemplate, userId);
        // 启动Agent-Plan模式
        PlanNotebook planNotebook = PlanNotebook.builder()
                .needUserConfirm(false) // 无需用户手动确认
                .storage(redisPlanStorage)
                .build();
        // 给PlanNoteBook挂载一个Hook, 做计划进度推送
        if(agentRuntimeContext.getSessionId() != null){
            planNotebook.addChangeHook("plan", (planNoteBook, plan) -> progressNotificationHook.sendPlanUpdate(agentRuntimeContext.getSessionId(), plan));
        }

        // 规划智能体只组合上游目的地咨询和天气结果，不虚构未接入的交通、酒店或价格数据。
        return ReActAgent.builder()
                .name("ItineraryPlanningAgent")
                .description("结合目的地咨询和天气信息，根据出差目标与约束生成交通、住宿及日程候选方案，并说明取舍、假设和信息缺口")
                .model(model)
                .sysPrompt(itineraryPlanningPrompt)
                .toolkit(toolkit)
                .skillBox(skillBox)
                .planNotebook(planNotebook)
                // 计划状态由 PersistPlanNoteHook 单独按会话加载和保存，避免与通用 Session Hook 重复写入。
                .statePersistence(StatePersistence.builder().planNotebookManaged(false).build())
                .toolExecutionContext(context)
                .maxIters(150)
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(60)).build()) // 工具执行超时配置
                .memory(new AutoContextMemory(autoContextConfig, model))
                // 搜索结果在通知及持久化前精简，确保后续模型推理读取替换后的消息。
                // skillContentCollapseHook 在推理前折叠历史技能正文，避免每轮重放整份 SKILL.md。
                .hooks(List.of(agentRegistryHook, persistSessionHook, persistPlanNoteHook, tuniuApiKeyShellHook,
                        tuniuSearchResultHook,
                        tuniuSearchResultCaptureHook, skillContentCollapseHook,
                        progressNotificationHook, modelUsageBillingHook, longTermMemoryRecordHook,
                        agentExecutionMonitorHook, agentTracePersistenceHook,
                        new AutoContextHook(), new PendingToolRecoveryHook(), activeAgentPersistenceHook, toolCircuitBreakerHook))
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL) // AGENT_CONTROL模式：给Agent提供两个工具（检索/存储）由Agent决定何时调用工具进行存储长期记忆, 以及召回用户长期记忆
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
                null,
                new TuniuShellCommandValidator());
        skillBox.codeExecution().withShell(shellTool).withRead().withWrite().enable();
        return skillBox;
    }

    /** 从 classpath 加载行程规划系统提示词，并在配置缺失时阻止 Agent 使用空提示词启动。 */
    private static String loadPrompt() {
        ClassPathResource resource = new ClassPathResource(PROMPT_RESOURCE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            String prompt = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (prompt.isEmpty()) {
                throw new IllegalStateException("行程规划系统提示词不能为空: " + PROMPT_RESOURCE_PATH);
            }
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载行程规划系统提示词: " + PROMPT_RESOURCE_PATH, exception);
        }
    }

    /** 使用当前用户标识和百炼 API Key 创建长期记忆实现。 */
    public LongTermMemory createLongTermMemory() {
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContextHolder.get();
        String userId = agentRuntimeContext.getUserId();

        // 使用当前认证用户作为记忆归属，避免不同用户共享长期记忆数据。
        BailianLongTermMemory longTermMemory = BailianLongTermMemory.builder()
                .apiKey(dashscopeApiKey)
                .userId(userId)
                .memoryLibraryId(memoryLibraryId)
                .metadata(Map.of("source", "corp-trip"))
                .topK(20)
                .build();

        CropTripLongTermMemory cropTripLongTermMemory = new CropTripLongTermMemory(longTermMemory, redisCache, userId);
//        // 使用自建 Mem0，需要指定 apiType 为 Mem0ApiType.SELF_HOSTED
//        Mem0LongTermMemory customMem0LongTermMemory = Mem0LongTermMemory.builder()
//                .agentName("SmartAssistant")

//                .userId(userId)
//                .timeout(Duration.ofMinutes(30))
//                .apiBaseUrl("http://localhost:8888")  // 自建 Mem0 服务地址
//                .apiType(Mem0ApiType.SELF_HOSTED)  // 指定为自建 Mem0
//                .build();
        return cropTripLongTermMemory;
    }
}

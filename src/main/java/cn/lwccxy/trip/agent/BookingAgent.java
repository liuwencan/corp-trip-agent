package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.hooks.*;
import cn.lwccxy.trip.agent.memory.CropTripLongTermMemory;
import cn.lwccxy.trip.agent.tools.*;
import cn.lwccxy.trip.common.utils.RedisCacheUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置通过途牛服务查询并执行机票、火车票及酒店预订的专职智能体。 */
@Configuration("bookingAgentConfiguration")
public class BookingAgent {

    private static final String PROMPT_RESOURCE_PATH = "sysprompt/BookingAgentPrompt.md";
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "curl", "npm", "npx", "tuniu", "cat", "grep", "node", "date", "env", "which", "bash", "ls");

    private final Model model;
    private final AgentSkillRepository agentSkillRepository;
    private final PersistSessionHook persistSessionHook;
    private final TuniuApiKeyShellHook tuniuApiKeyShellHook;
    private final TuniuSearchResultCaptureHook tuniuSearchResultCaptureHook;
    private final SkillContentCollapseHook skillContentCollapseHook;
    private final ProgressNotificationHook progressNotificationHook;
    private final ModelUsageBillingHook modelUsageBillingHook;
    private final AgentExecutionMonitorHook agentExecutionMonitorHook;
    private final TripOrderReadTools tripOrderReadTools;
    private final BookingRecordReadTools bookingRecordReadTools;
    private final BookingRecordWriteTools bookingRecordWriteTools;
    private final UserProfileReadTools userProfileReadTools;
    private final UserProfileWriteTools userProfileWriteTools;
    private final String bookingPrompt;
    private final ProviderApiKeyTools apiKeyTools;
    private final ItineraryPlanReadTools itineraryPlanReadTools;
    private final String dashscopeApiKey;
    private final String memoryLibraryId;
    private final RedisCacheUtil redisCache;
    private final BookingPersistenceHook bookingPersistenceHook;

    /** 创建预订智能体配置并加载受控的预订流程提示词。 */
    public BookingAgent(Model model, AgentSkillRepository agentSkillRepository,
                        PersistSessionHook persistSessionHook, TuniuApiKeyShellHook tuniuApiKeyShellHook,
                        TuniuSearchResultHook tuniuSearchResultHook,
                        TuniuSearchResultCaptureHook tuniuSearchResultCaptureHook,
                        SkillContentCollapseHook skillContentCollapseHook,
                        ProgressNotificationHook progressNotificationHook,
                        ModelUsageBillingHook modelUsageBillingHook,
                        AgentExecutionMonitorHook agentExecutionMonitorHook,
                        TripOrderReadTools tripOrderReadTools,
                        BookingRecordReadTools bookingRecordReadTools,
                        BookingRecordWriteTools bookingRecordWriteTools,
                        BookingPersistenceHook bookingPersistenceHook,
                        UserProfileReadTools userProfileReadTools,
                        UserProfileWriteTools userProfileWriteTools,
                        ProviderApiKeyTools apiKeyTools,
                        ItineraryPlanReadTools itineraryPlanReadTools,
                        @Value("${corp.trip.bailian.dashscope-api}") String dashscopeApiKey,
                        @Value("${corp.trip.bailian.memory-library-id}") String memoryLibraryId,
                        RedisCacheUtil redisCache) {
        this.model = model;
        this.agentSkillRepository = agentSkillRepository;
        this.persistSessionHook = persistSessionHook;
        this.tuniuApiKeyShellHook = tuniuApiKeyShellHook;
        this.tuniuSearchResultCaptureHook = tuniuSearchResultCaptureHook;
        this.skillContentCollapseHook = skillContentCollapseHook;
        this.progressNotificationHook = progressNotificationHook;
        this.modelUsageBillingHook = modelUsageBillingHook;
        this.agentExecutionMonitorHook = agentExecutionMonitorHook;
        this.tripOrderReadTools = tripOrderReadTools;
        this.bookingRecordReadTools = bookingRecordReadTools;
        this.bookingRecordWriteTools = bookingRecordWriteTools;
        this.bookingPersistenceHook = bookingPersistenceHook;
        this.userProfileReadTools = userProfileReadTools;
        this.userProfileWriteTools = userProfileWriteTools;
        this.bookingPrompt = loadPrompt();
        this.apiKeyTools = apiKeyTools;
        this.itineraryPlanReadTools = itineraryPlanReadTools;
        this.dashscopeApiKey = dashscopeApiKey;
        this.memoryLibraryId = memoryLibraryId;
        this.redisCache = redisCache;
    }

    /** 为当前会话创建隔离的预订智能体并注入用户认证上下文。 */
    @Bean
    @Scope("prototype")
    public ReActAgent bookingAgent() {
        AgentRuntimeContext runtimeContext = AgentRuntimeContextHolder.get();
        // 工具执行上下文绑定当前认证用户，凭证 Hook 据此注入对应的途牛 API Key。
        ToolExecutionContext executionContext = ToolExecutionContext.builder()
                .register(runtimeContext)
                .build();

        Toolkit toolkit = new Toolkit();
        // 差旅单查询工具
        toolkit.registerTool(tripOrderReadTools);
        // 通过正式预订记录工具查询订单，并在供应商结果确认后保存或同步取消状态。
        toolkit.registerTool(bookingRecordReadTools);
        toolkit.registerTool(bookingRecordWriteTools);
        // 用户个人信息读工具： 预定下单需要用户真实信息
        toolkit.registerTool(userProfileReadTools);
        // 用户个人信息写工具：当读取到用户的个人信息不足时, 例如缺少手机号时, 会要求用户提供, 并调用此工具保存用户信息
        toolkit.registerTool(userProfileWriteTools);
        // 在编写预定等CLI命令时需要用到用户的APIKEY去做鉴权
        toolkit.registration().tool(apiKeyTools).apply();
        // 用户做预定下单时, 需要感知到用户此行程规划出来的方案
        toolkit.registration().tool(itineraryPlanReadTools).apply();

        SkillBox skillBox = buildSkillBox(toolkit);

        // 长期记忆
        LongTermMemory longTermMemory = createLongTermMemory();

        // 搜索结果先精简再捕获，避免重复结构化内容进入模型上下文，同时保留后续选品所需的候选。
        return ReActAgent.builder()
                .name("BookingAgent")
                .description("通过途牛服务搜索、比较并在用户明确确认后预订机票、火车票和酒店")
                .model(model)
                .sysPrompt(bookingPrompt)
                .toolkit(toolkit)
                .skillBox(skillBox)
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .toolExecutionContext(executionContext)
                .maxIters(10) // 执行轮数
                .toolExecutionConfig(ExecutionConfig.builder().timeout(Duration.ofMinutes(30)).build())
                .memory(new AutoContextMemory(AutoContextConfig.builder().build(), model))
                .hooks(List.of(persistSessionHook, bookingPersistenceHook, tuniuApiKeyShellHook,
                        progressNotificationHook, modelUsageBillingHook, agentExecutionMonitorHook, new AutoContextHook()))
                .build();
    }

    /** 注册途牛预订技能、受限 Shell 命令和只读技能文件访问能力。 */
    private SkillBox buildSkillBox(Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);
        // 技能文件定义各供应商工具名、必填参数及真实资源 ID 的来源。
        skillBox.registerSkill(agentSkillRepository.getSkill("tuniu-cli"));
        ShellCommandTool shellTool = new ShellCommandTool(
                null, ALLOWED_COMMANDS, null, new TuniuShellCommandValidator());
        // 预订流程只需读取技能参考，不开放文件写入能力。
        skillBox.codeExecution().withShell(shellTool).withRead().enable();
        return skillBox;
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

    /** 从 classpath 加载预订规则；提示词缺失时阻止构造能力不完整的 Agent。 */
    private static String loadPrompt() {
        try (InputStream inputStream = new ClassPathResource(PROMPT_RESOURCE_PATH).getInputStream()) {
            String prompt = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (prompt.isEmpty()) {
                throw new IllegalStateException("预订智能体提示词为空: " + PROMPT_RESOURCE_PATH);
            }
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载预订智能体提示词: " + PROMPT_RESOURCE_PATH, exception);
        }
    }
}

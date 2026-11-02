package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.apikey.service.ProviderApiKeyService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 在行程规划智能体执行途牛 CLI 命令前注入当前用户的 API Key。 */
@Component
public class TuniuApiKeyShellHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(TuniuApiKeyShellHook.class);
    private static final int HOOK_PRIORITY = 10;
    private static final String TARGET_AGENT = "ItineraryPlanningAgent";
    private static final String SHELL_TOOL = "execute_shell_command";
    private static final String COMMAND_ARGUMENT = "command";
    private static final Pattern TUNIU_CALL_PATTERN = Pattern.compile("(?i)(?<![\\w./-])tuniu\\s+call\\b");
    private static final Pattern API_KEY_ASSIGNMENT_PATTERN = Pattern.compile("(?i)\\bTUNIU_API_KEY\\s*=");

    private final ProviderApiKeyService providerApiKeyService;

    /** 创建途牛 CLI 鉴权 Hook。 */
    public TuniuApiKeyShellHook(ProviderApiKeyService providerApiKeyService) {
        this.providerApiKeyService = providerApiKeyService;
    }

    /**
     * 返回较高优先级，使凭证注入先于进度事件处理执行。
     *
     * @return Tuniu Hook 优先级，数值越小越先执行
     */
    @Override
    public int priority() {
        return HOOK_PRIORITY;
    }

    /**
     * 在途牛 CLI 调用前按当前会话用户加载并注入 API Key。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 修改后的事件；缺少用户凭证时以错误终止工具调用
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreActingEvent actingEvent)
                || !TARGET_AGENT.equals(event.getAgent().getName())) {
            return Mono.just(event);
        }

        ToolUseBlock toolUse = actingEvent.getToolUse();
        if (toolUse == null || !SHELL_TOOL.equals(toolUse.getName())) {
            return Mono.just(event);
        }
        Object commandValue = toolUse.getInput() == null ? null : toolUse.getInput().get(COMMAND_ARGUMENT);
        if (!(commandValue instanceof String command) || !containsTuniuCall(command)) {
            return Mono.just(event);
        }
        if (API_KEY_ASSIGNMENT_PATTERN.matcher(command).find()) {
            return Mono.error(new IllegalArgumentException("途牛 CLI 命令不能自行设置 TUNIU_API_KEY"));
        }

        return Mono.deferContextual(contextView -> {
            // 用户身份从本次 Agent 调用的 Reactor 上下文读取，避免共享 Hook 保存跨请求状态。
            AgentRuntimeContext runtimeContext = contextView.getOrDefault("agentRuntimeContext", null);
            if (runtimeContext == null || runtimeContext.getUserId() == null
                    || runtimeContext.getUserId().isBlank()) {
                return Mono.error(new IllegalStateException("当前会话缺少用户信息，无法调用途牛服务"));
            }

            try {
                Long userId = Long.valueOf(runtimeContext.getUserId().trim());
                String apiKey = providerApiKeyService.getActiveApiKey(userId, "TUNIU");
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("当前用户尚未配置可用的 TUNIU API Key"));
                }

                // 仅在 Shell 子进程环境中设置凭证，避免影响应用进程和其他并发用户。
                String modifiedCommand = prependApiKeyEnvironment(command, apiKey);
                // 记录改写前后命令便于审计，但改写后的日志必须遮蔽解密后的 API Key。
                logger.info("[TUNIU][COMMAND_REWRITE] userId={}, originalCommand={}, rewrittenCommand={}",
                        userId, command, maskInjectedApiKey(modifiedCommand));
                Map<String, Object> modifiedInput = new HashMap<>(toolUse.getInput());
                modifiedInput.put(COMMAND_ARGUMENT, modifiedCommand);
                ToolUseBlock modifiedToolUse = ToolUseBlock.builder()
                        .id(toolUse.getId())
                        .name(toolUse.getName())
                        .content(toolUse.getContent())
                        .metadata(toolUse.getMetadata())
                        .input(modifiedInput)
                        .build();
                actingEvent.setToolUse(modifiedToolUse);
                return Mono.just(event);
            } catch (NumberFormatException exception) {
                return Mono.error(new IllegalStateException("当前会话用户信息无效，无法调用途牛服务"));
            }
        });
    }

    /**
     * 判断 Shell 命令中是否包含途牛 CLI 的业务调用。
     *
     * @param command Shell 命令文本
     * @return 包含 tuniu call 时返回 true
     */
    static boolean containsTuniuCall(String command) {
        return command != null && TUNIU_CALL_PATTERN.matcher(command).find();
    }

    /**
     * 按当前操作系统生成 Shell 环境变量前缀。
     *
     * @param command 原始途牛命令
     * @param apiKey 当前用户解密后的 API Key
     * @return 注入凭证环境变量后的命令
     */
    static String prependApiKeyEnvironment(String command, String apiKey) {
        if (isWindows()) {
            return "set \"TUNIU_API_KEY=" + quoteForWindowsCmd(apiKey) + "\" && " + command;
        }
        return "export TUNIU_API_KEY=" + quoteForPosixShell(apiKey) + "; " + command;
    }

    /**
     * 遮蔽改写命令中的 API Key，避免审计日志记录凭证明文。
     *
     * @param command 已注入 API Key 的命令
     * @return API Key 已脱敏的命令
     */
    private static String maskInjectedApiKey(String command) {
        return command
                .replaceFirst("(?is)(set\\s+\"TUNIU_API_KEY=)[^\"]*(\"\\s*&&\\s*)", "$1[已隐藏]$2")
                .replaceFirst("(?is)(export\\s+TUNIU_API_KEY=)(?:'[^']*'|\"[^\"]*\"|[^;\\s]+)(;\\s*)",
                        "$1'[已隐藏]'$2");
    }

    /**
     * 判断当前 AgentScope ShellCommandTool 使用的命令解释器类型。
     *
     * @return Windows 系统返回 true
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 校验 Windows cmd 的 set 命令值，拒绝可能改变命令结构的特殊字符。
     *
     * @param value 待写入环境变量的 API Key
     * @return 可嵌入双引号 set 语句的值
     */
    private static String quoteForWindowsCmd(String value) {
        if (value.chars().anyMatch(character -> character == '\r' || character == '\n'
                || character == '%' || character == '&' || character == '|'
                || character == '<' || character == '>' || character == '^'
                || character == '"')) {
            throw new IllegalArgumentException("TUNIU API Key 含有不支持的命令特殊字符");
        }
        return value;
    }

    /**
     * 将密钥转义为 POSIX Shell 单引号字符串。
     *
     * @param value 待转义的密钥
     * @return 可安全嵌入 Shell 单引号赋值的字符串
     */
    private static String quoteForPosixShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

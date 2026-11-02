package cn.lwccxy.trip.agent.hooks;

import io.agentscope.core.tool.coding.CommandValidator;
import io.agentscope.core.tool.coding.UnixCommandValidator;
import io.agentscope.core.tool.coding.WindowsCommandValidator;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 僅允許途牛 API Key 環境變數前綴包裝單一 tuniu call 命令。 */
public class TuniuShellCommandValidator implements CommandValidator {

    private static final Pattern WINDOWS_TUNIU_COMMAND = Pattern.compile(
            "(?is)^\\s*set\\s+\"TUNIU_API_KEY=[^\"]+\"\\s*&&\\s*(tuniu\\s+call\\b.*)$");
    private static final Pattern UNIX_TUNIU_COMMAND = Pattern.compile(
            "(?is)^\\s*export\\s+TUNIU_API_KEY=(?:'[^']*'|\"[^\"]*\"|[^;]+);\\s*(tuniu\\s+call\\b.*)$");

    private final CommandValidator delegate;

    /** 创建委托当前操作系统默认命令规则的途牛命令校验器。 */
    public TuniuShellCommandValidator() {
        // 与 AgentScope ShellCommandTool 一致，按宿主操作系统选择默认校验规则。
        this.delegate = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new WindowsCommandValidator()
                : new UnixCommandValidator();
    }

    /**
     * 校验命令；仅将固定凭证前缀后的单条途牛调用交由默认校验器判断。
     *
     * @param command 完整 Shell 命令
     * @param allowedCommands Shell 工具允许执行的命令白名单
     * @return 命令校验结果
     */
    @Override
    public ValidationResult validate(String command, Set<String> allowedCommands) {
        String tuniuCommand = unwrapTuniuCommand(command);
        return tuniuCommand == null
                ? delegate.validate(command, allowedCommands)
                : delegate.validate(tuniuCommand, allowedCommands);
    }

    /**
     * 提取包装命令实际调用的可执行程序。
     *
     * @param command Shell 命令
     * @return 可执行程序名称
     */
    @Override
    public String extractExecutable(String command) {
        String tuniuCommand = unwrapTuniuCommand(command);
        return delegate.extractExecutable(tuniuCommand == null ? command : tuniuCommand);
    }

    /**
     * 判断命令是否包含多个命令；仅忽略已识别包装中的环境变量分隔符。
     *
     * @param command Shell 命令
     * @return 含有额外命令分隔符时返回 true
     */
    @Override
    public boolean containsMultipleCommands(String command) {
        String tuniuCommand = unwrapTuniuCommand(command);
        return delegate.containsMultipleCommands(tuniuCommand == null ? command : tuniuCommand);
    }

    /**
     * 识别并取出凭证前缀后的单条 tuniu call，拒绝把前缀用于其他命令。
     *
     * @param command 完整 Shell 命令
     * @return 单条途牛命令；不符合受限格式时返回 null
     */
    private String unwrapTuniuCommand(String command) {
        if (command == null) {
            return null;
        }

        Pattern pattern = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? WINDOWS_TUNIU_COMMAND
                : UNIX_TUNIU_COMMAND;
        Matcher matcher = pattern.matcher(command);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }
}

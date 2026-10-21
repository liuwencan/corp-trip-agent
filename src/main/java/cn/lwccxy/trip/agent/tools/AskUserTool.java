package cn.lwccxy.trip.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;

import java.util.List;
import java.util.Map;

/**
 * 询问用户工具
 */
public class AskUserTool {

    /**
     * 向用户请求澄清或补充信息。
     *
     * <p>此方法始终抛出 {@link ToolSuspendException} 以暂停智能体执行。
     * 框架会将其转换为待处理的 {@code ToolResultBlock}，并设置
     * {@code GenerateReason.TOOL_SUSPENDED}，以便前端提取工具输入参数并渲染相应的界面组件。
     *
     * @param question     要向用户提出的问题
     * @param uiType       界面组件类型，默认为 "text"
     * @param options      单选或多选组件的选项
     * @param fields       表单组件的字段定义
     * @param defaultValue 输入字段的默认值
     * @param allowOther   为 true 时，在单选或多选组件中添加允许用户输入自定义内容的“其他”选项
     * @return 此方法不会正常返回
     * @throws ToolSuspendException 始终抛出此异常以暂停智能体执行
     */
    @Tool(
            name = "ask_user",
            description =
                    "Ask the user for clarification or additional information when the request is"
                            + " ambiguous or missing required details. Choose the appropriate ui_type:"
                            + " 'text' for free-form input, 'select' for choosing one from a list"
                            + " (provide options), 'multi_select' for choosing multiple from a list,"
                            + " 'confirm' for yes/no questions, 'form' for collecting multiple fields"
                            + " at once (provide fields), 'date' for date selection, 'number' for"
                            + " numeric input.")
    public String askUser(
            @ToolParam(name = "question", description = "The question to ask the user")
            String question,
            @ToolParam(
                    name = "ui_type",
                    description =
                            "UI component type: text, select, multi_select, confirm, form,"
                                    + " date, number. Defaults to 'text'.",
                    required = false)
            String uiType,
            @ToolParam(
                    name = "options",
                    description =
                            "Options for select/multi_select. Simple string array,"
                                    + " e.g. [\"Beijing\", \"Shanghai\", \"Tokyo\"]",
                    required = false)
            List<String> options,
            @ToolParam(
                    name = "fields",
                    description =
                            "Field definitions for 'form' ui_type. Array of objects with"
                                    + " name, label, type (text/number/date/select/textarea),"
                                    + " placeholder, required, options, min, max, step.",
                    required = false)
            List<Map<String, Object>> fields,
            @ToolParam(
                    name = "default_value",
                    description = "Default value for the input field (string only)",
                    required = false)
            Object defaultValue,
            @ToolParam(
                    name = "allow_other",
                    description =
                            "If true, adds an 'Other' option with a free-text input so"
                                    + " users can enter custom values not in the predefined"
                                    + " list. Use with select or multi_select.",
                    required = false)
            Boolean allowOther) {
        String reason = question != null ? question : "Waiting for user input";
        throw new ToolSuspendException(reason);
    }
}

package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.tools.store.TuniuSearchCandidateStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 在途牛搜索结果精简后捕获候选方案，并按用户写入 Redis Hash。 */
@Component
public class TuniuSearchResultCaptureHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(TuniuSearchResultCaptureHook.class);
    private static final int HOOK_PRIORITY = 30;
    private static final String SHELL_TOOL = "execute_shell_command";
    private static final Pattern SEARCH_COMMAND = Pattern.compile(
            "(?i)(?<![\\w./-])tuniu\\s+call\\s+(?:(?<flight>flight)\\s+searchLowestPriceFlight"
                    + "|(?<train>train)\\s+searchLowestPriceTrain|(?<hotel>hotel)\\s+tuniuHotelSearch)(?=\\s|$)");

    private final TuniuSearchCandidateStore candidateStore;

    /** 创建无 Redis 的测试 Hook，非 Spring 运行路径不会写入候选方案。 */
    public TuniuSearchResultCaptureHook() {
        this.candidateStore = null;
    }

    /**
     * 创建途牛搜索结果捕获 Hook。
     *
     * @param candidateStore 途牛搜索候选方案 Redis 存储层
     */
    @Autowired
    public TuniuSearchResultCaptureHook(TuniuSearchCandidateStore candidateStore) {
        this.candidateStore = candidateStore;
    }

    /**
     * 返回高于精简 Hook 的优先级，确保捕获到精简后的工具结果。
     *
     * @return 捕获 Hook 优先级
     */
    @Override
    public int priority() {
        return HOOK_PRIORITY;
    }

    /**
     * 捕获途牛搜索完成事件并写入用户专属 Redis Hash，旁路失败不影响主流程。
     *
     * @param event AgentScope Hook 事件
     * @param <T> 事件类型
     * @return 原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent actingEvent)) {
            return Mono.just(event);
        }
        return Mono.deferContextual(contextView -> {
            try {
                capture(actingEvent, contextView);
            } catch (RuntimeException exception) {
                // Redis 或结果解析异常不能中断后续模型推理和工具链。
                logger.warn("[途牛搜索候选捕获] 写入 Redis 失败，toolCallId={}",
                        actingEvent.getToolUse() == null ? null : actingEvent.getToolUse().getId(), exception);
            }
            return Mono.just(event);
        });
    }

    /**
     * 校验搜索命令、解析业务结果并保存候选方案。
     *
     * @param event 工具完成事件
     * @param contextView Reactor 运行时上下文
     */
    private void capture(PostActingEvent event, ContextView contextView) {
        ToolUseBlock toolUse = event.getToolUse();
        SearchType searchType = resolveSearchType(toolUse);
        if (searchType == null) {
            return;
        }
        AgentRuntimeContext runtimeContext = contextView.getOrDefault("agentRuntimeContext", null);
        String userId = normalize(runtimeContext == null ? null : runtimeContext.getUserId());
        if (userId == null) {
            logger.warn("[途牛搜索候选捕获] 缺少用户上下文，跳过写入，toolCallId={}", toolUse.getId());
            return;
        }
        if (candidateStore == null) {
            return;
        }

        JSONObject response = parseShellResult(event.getToolResult());
        if (response == null || Boolean.FALSE.equals(response.getBoolean("success"))) {
            return;
        }
        JSONObject businessData = unwrapBusinessData(response);
        if (businessData == null) {
            return;
        }

        String command = String.valueOf(toolUse.getInput().get("command"));
        JSONObject args = parseCommandArgs(command);
        String field = buildField(searchType, args);
        JSONObject entry = new JSONObject();
        entry.put("type", searchType.name().toLowerCase());
        entry.put("args", args);
        entry.put("data", businessData);

        String oldValue = candidateStore.get(userId, field);
        JSONObject merged = oldValue == null ? entry : mergeEntries(parseObject(oldValue), entry, searchType);
        candidateStore.put(userId, field, merged);
        logger.info("[途牛搜索候选捕获] userId={}, field={}, type={}, candidateCount={}",
                userId, field, searchType.name().toLowerCase(), candidateCount(merged, searchType));
    }

    /** 判断工具调用并返回搜索类型。 */
    private SearchType resolveSearchType(ToolUseBlock toolUse) {
        if (toolUse == null || !SHELL_TOOL.equals(toolUse.getName()) || toolUse.getInput() == null
                || !(toolUse.getInput().get("command") instanceof String command)) {
            return null;
        }
        var matcher = SEARCH_COMMAND.matcher(command);
        if (!matcher.find()) {
            return null;
        }
        if (matcher.group("flight") != null) {
            return SearchType.FLIGHT;
        }
        return matcher.group("train") != null ? SearchType.TRAIN : SearchType.HOTEL;
    }

    /** 从工具文本块中提取标准输出 JSON。 */
    private JSONObject parseShellResult(ToolResultBlock toolResult) {
        if (toolResult == null || toolResult.isSuspended() || toolResult.getOutput() == null) {
            return null;
        }
        for (ContentBlock block : toolResult.getOutput()) {
            if (!(block instanceof TextBlock textBlock) || textBlock.getText() == null) {
                continue;
            }
            String text = textBlock.getText();
            int start = text.indexOf("<stdout>");
            int end = text.lastIndexOf("</stdout>");
            if (start >= 0 && end > start) {
                text = text.substring(start + "<stdout>".length(), end).trim();
            }
            JSONObject parsed = parseObject(text);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** 解包 AgentScope/MCP 外层响应，取得实际候选 JSON 对象。 */
    private JSONObject unwrapBusinessData(JSONObject response) {
        JSONObject result = response.getJSONObject("result");
        if (result == null) {
            result = response;
        }
        JSONArray content = result.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return result;
        }
        for (Object item : content) {
            if (item instanceof JSONObject block && block.get("text") instanceof String text) {
                JSONObject business = parseObject(text);
                if (business != null) {
                    return business;
                }
            }
        }
        return null;
    }

    /** 合并同一查询条件下的新旧候选并按业务标识去重。 */
    private JSONObject mergeEntries(JSONObject oldEntry, JSONObject newEntry, SearchType type) {
        JSONObject mergedEntry = new JSONObject(newEntry);
        JSONObject oldData = oldEntry == null ? null : oldEntry.getJSONObject("data");
        JSONObject newData = newEntry.getJSONObject("data");
        String listField = listField(type, oldData, newData);
        JSONArray oldList = oldData == null ? null : oldData.getJSONArray(listField);
        JSONArray newList = newData == null ? null : newData.getJSONArray(listField);
        if (oldList == null || newList == null) {
            return mergedEntry;
        }
        Map<String, JSONObject> deduplicated = new LinkedHashMap<>();
        addItems(deduplicated, oldList, type);
        addItems(deduplicated, newList, type);
        JSONObject mergedData = new JSONObject(newData);
        mergedData.put(listField, new JSONArray(deduplicated.values()));
        mergedEntry.put("data", mergedData);
        return mergedEntry;
    }

    /** 将结果数组加入去重映射，新结果覆盖相同业务标识的旧结果。 */
    private void addItems(Map<String, JSONObject> target, JSONArray items, SearchType type) {
        for (Object item : items) {
            if (item instanceof JSONObject object) {
                target.put(dedupKey(object, type), object);
            }
        }
    }

    /** 生成候选方案业务唯一键。 */
    private String dedupKey(JSONObject item, SearchType type) {
        String key = switch (type) {
            case FLIGHT -> joinKey(item.getString("flightNumber"), item.getString("departureTime"));
            case TRAIN -> joinKey(item.getString("trainNum"), item.getString("departureTime"));
            case HOTEL -> firstText(item.getString("hotelId"), item.getString("hotelName"));
        };
        return key == null ? item.toJSONString() : key;
    }

    /** 确定不同供应商结果使用的候选列表字段。 */
    private String listField(SearchType type, JSONObject oldData, JSONObject newData) {
        return type == SearchType.HOTEL && (hasArray(newData, "hotels") || hasArray(oldData, "hotels"))
                ? "hotels" : "data";
    }

    /** 生成当前搜索条件的 Hash field。 */
    private String buildField(SearchType type, JSONObject args) {
        return switch (type) {
            case FLIGHT, TRAIN -> TuniuSearchCandidateStore.routeField(type.name().toLowerCase(),
                    firstText(args, "departureCityName", "departureCity"),
                    firstText(args, "arrivalCityName", "arrivalCity", "destinationCity"),
                    firstText(args, "departureDate", "date"));
            case HOTEL -> TuniuSearchCandidateStore.hotelField(
                    firstText(args, "cityName", "city", "destinationCity"),
                    firstText(args, "checkInDate", "checkIn"),
                    firstText(args, "checkOutDate", "checkOut"));
        };
    }

    /** 解析命令中的 JSON 参数。 */
    private JSONObject parseCommandArgs(String command) {
        int start = command.indexOf('{');
        int end = command.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new JSONObject();
        }
        JSONObject parsed = parseObject(command.substring(start, end + 1));
        return parsed == null ? new JSONObject() : parsed;
    }

    /** 解析 JSON 对象并隔离异常。 */
    private JSONObject parseObject(String text) {
        if (text == null || text.isBlank() || !JSON.isValidObject(text)) {
            return null;
        }
        try {
            return JSON.parseObject(text);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 获取业务列表数量用于审计日志。 */
    private int candidateCount(JSONObject entry, SearchType type) {
        JSONObject data = entry.getJSONObject("data");
        if (data == null) {
            return 0;
        }
        String field = type == SearchType.HOTEL && data.getJSONArray("hotels") != null ? "hotels" : "data";
        JSONArray items = data.getJSONArray(field);
        return items == null ? 0 : items.size();
    }

    /** 获取第一个非空对象字段。 */
    private String firstText(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            String value = normalize(object.getString(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 获取两个值拼接后的去重键。 */
    private String joinKey(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        return left == null || right == null ? null : left + "|" + right;
    }

    /** 获取第一个非空字符串。 */
    private String firstText(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    /** 判断 JSON 字段是否为数组。 */
    private boolean hasArray(JSONObject object, String key) {
        return object != null && object.getJSONArray(key) != null;
    }

    /** 清理文本参数。 */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 搜索业务类型。 */
    private enum SearchType {
        FLIGHT,
        TRAIN,
        HOTEL
    }
}

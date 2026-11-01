package cn.lwccxy.trip.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.ToString;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 实时联网查询工具集
 */
public class RealtimeDestinationTools {

    @Tool(name = "query_weather", description = "查询目标城市的天气, 注意该工具只支持查询3天内范围的天气" +
            "，今天/明天/后天，超过这个时间范围的话都不可以查询, 需要切换到MCP天气查询工具")
    public String weather(@ToolParam(name = "city", description = "目标城市, 例如 广州、北京、上海、杭州、深圳") String city,
                          @ToolParam(name = "date", description = "查询日期, 应该传入的参数格式为 2026-10-25, " +
                                  "仅仅支持查询三天范围内的天气 , 如果不填的话默认查询三天内的天气", required = false) String date){

        LocalDate curDate = LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
//        if (targetDate != null) {
//            LocalDate maxDate = LocalDate.now().plusDays(WEATHER_FORECAST_DAYS);
//            if (targetDate.isAfter(maxDate)) {
//                logger.info("[TOOL][query_weather] 日期 {} 超出 wttr.in 范围，提示改用天气 MCP 工具", date);
//                JSONObject beyond = new JSONObject();
//                beyond.put("city", city);
//                beyond.put("date", date);
//                beyond.put("beyond_range", true);
//                beyond.put("message",
//                        date + " 超出 wttr.in 支持范围（仅今天起 3 天内）。"
//                                + "请立即改用『城市15日预报』工具（更远日期用『城市40日预报』）查询该日期的天气预报。");
//                return JSON.toJSONString(beyond);
//            }
//        }

        return null;
    }

}

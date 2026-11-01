package cn.lwccxy.trip.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** 提供目的地天气和目的地最新资讯的实时查询工具。 */
@Component
public class DestinationLiveTools {

    private static final Logger logger = LoggerFactory.getLogger(DestinationLiveTools.class);
    private static final String WEATHER_API_BASE = "https://wttr.in/";
    private static final int WEATHER_FORECAST_DAYS = 2;
    private static final String NEWS_PAGE_SIZE = "5";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String newsApiKey;

    /** 创建实时目的地查询工具，并读取可选的 NewsData.io 密钥。 */
    public DestinationLiveTools(@Value("${corp.trip.news-api-key:}") String newsApiKey) {
        this.newsApiKey = newsApiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 查询城市当前天气及短期预报，支持按日期筛选今天起三天内的数据。 */
    @Tool(name = "query_weather", description = "联网查询目的地城市当前天气及未来三天预报，支持按 YYYY-MM-DD 日期筛选；仅覆盖今天至后天，超出范围请改用已注册的天气 MCP 工具。")
    public String queryWeather(
            @ToolParam(name = "city", description = "目标城市，例如北京、上海、杭州、Tokyo") String city,
            @ToolParam(name = "date", description = "查询日期，格式 YYYY-MM-DD；可选，不填时返回当前天气和可用的三天预报", required = false) String date) {
        String normalizedCity = city == null ? "" : city.trim();
        JSONObject result = new JSONObject();
        result.put("city", normalizedCity);
        result.put("source", "wttr.in");

        // 先验证城市和日期范围，避免无效输入触发外部请求或让模型误用超期预报。
        if (normalizedCity.isBlank()) {
            result.put("available", false);
            result.put("error", "请提供目标城市");
            return JSON.toJSONString(result);
        }
        LocalDate targetDate;
        try {
            targetDate = parseDate(date);
        } catch (DateTimeParseException e) {
            result.put("available", false);
            result.put("error", "日期格式无效，请使用 YYYY-MM-DD");
            return JSON.toJSONString(result);
        }
        if (targetDate != null && (targetDate.isBefore(LocalDate.now())
                || targetDate.isAfter(LocalDate.now().plusDays(WEATHER_FORECAST_DAYS)))) {
            result.put("available", false);
            result.put("date", date);
            result.put("beyond_range", true);
            result.put("message", "wttr.in 仅支持今天至后天的预报，请改用可查询更长周期的天气 MCP 工具");
            return JSON.toJSONString(result);
        }

        try {
            // 获取 wttr.in 的结构化 JSON，再按目标日期提取当前天气或逐日预报。
            String encodedCity = URLEncoder.encode(normalizedCity, StandardCharsets.UTF_8);
            JSONObject raw = JSON.parseObject(sendGet(WEATHER_API_BASE + encodedCity + "?format=j1"));
            result.put("available", true);
            result.put("queryDate", targetDate == null ? "today" : targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
            if (targetDate == null || targetDate.equals(LocalDate.now())) {
                JSONArray currentArray = raw.getJSONArray("current_condition");
                if (currentArray != null && !currentArray.isEmpty()) {
                    JSONObject current = currentArray.getJSONObject(0);
                    JSONObject weather = new JSONObject();
                    weather.put("temperatureC", current.getString("temp_C"));
                    weather.put("feelsLikeC", current.getString("FeelsLikeC"));
                    weather.put("humidityPercent", current.getString("humidity"));
                    weather.put("windKmph", current.getString("windspeedKmph"));
                    weather.put("description", firstDescription(current.getJSONArray("weatherDesc")));
                    result.put("current", weather);
                }
            }
            JSONArray forecastArray = raw.getJSONArray("weather");
            List<JSONObject> forecasts = new ArrayList<>();
            if (forecastArray != null) {
                for (int i = 0; i < forecastArray.size(); i++) {
                    JSONObject day = forecastArray.getJSONObject(i);
                    LocalDate forecastDate = parseDate(day.getString("date"));
                    if (targetDate != null && !targetDate.equals(forecastDate)) {
                        continue;
                    }
                    JSONObject forecast = new JSONObject();
                    forecast.put("date", day.getString("date"));
                    forecast.put("maxTempC", day.getString("maxtempC"));
                    forecast.put("minTempC", day.getString("mintempC"));
                    forecast.put("description", firstDescription(day.getJSONArray("hourly") == null
                            || day.getJSONArray("hourly").isEmpty() ? null
                            : day.getJSONArray("hourly").getJSONObject(0).getJSONArray("weatherDesc")));
                    forecasts.add(forecast);
                }
            }
            result.put("forecast", forecasts);
            if (targetDate != null && forecasts.isEmpty()) {
                result.put("available", false);
                result.put("message", "天氣服務未返回指定日期的預報");
            }
            return JSON.toJSONString(result);
        } catch (Exception e) {
            logger.warn("[DestinationLiveTools] 天气查询失败，city={}", normalizedCity);
            result.put("available", false);
            result.put("error", "天气查询暂时不可用，请稍后重试");
            return JSON.toJSONString(result);
        }
    }

    /** 查询目的地最新资讯，API 密钥未配置时返回明确的不可用状态。 */
    @Tool(name = "query_destination_news", description = "联网查询目的地最新资讯，可关注 traffic（交通）、event（活动）、safety（安全）、flight（航班）、hotel（住宿）、policy（政策）或 general（综合）；需配置 app.news-api-key。")
    public String queryDestinationNews(
            @ToolParam(name = "city", description = "目标城市，例如北京、上海、杭州、深圳") String city,
            @ToolParam(name = "topic", description = "关注主题：traffic、event、safety、flight、hotel、policy、general；可选", required = false) String topic) {
        String normalizedCity = city == null ? "" : city.trim();
        JSONObject result = new JSONObject();
        result.put("city", normalizedCity);
        result.put("topic", topic == null || topic.isBlank() ? "general" : topic.trim());

        // 新闻服务需要独立密钥；未配置时保留工具可调用性并明确告知模型能力边界。
        if (normalizedCity.isBlank()) {
            result.put("available", false);
            result.put("error", "请提供目标城市");
            return JSON.toJSONString(result);
        }
        if (newsApiKey == null || newsApiKey.isBlank()) {
            result.put("available", false);
            result.put("note", "目的地资讯查询未启用，请配置 app.news-api-key 后使用");
            return JSON.toJSONString(result);
        }

        try {
            // 通过 NewsData.io 查询中文资讯，限制返回数量并保留来源和发布时间供行程建议引用。
            String query = normalizedCity + " " + topicKeyword(topic);
            String uri = "https://newsdata.io/api/1/news?apikey="
                    + URLEncoder.encode(newsApiKey, StandardCharsets.UTF_8)
                    + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&language=zh&size=" + NEWS_PAGE_SIZE;
            JSONObject raw = JSON.parseObject(sendGet(uri));
            JSONArray articles = raw.getJSONArray("results");
            List<JSONObject> news = new ArrayList<>();
            if (articles != null) {
                for (int i = 0; i < articles.size(); i++) {
                    JSONObject article = articles.getJSONObject(i);
                    JSONObject item = new JSONObject();
                    item.put("title", article.getString("title"));
                    item.put("description", article.getString("description"));
                    item.put("publishedAt", article.getString("pubDate"));
                    item.put("source", article.getString("source_id"));
                    news.add(item);
                }
            }
            result.put("available", true);
            result.put("source", "newsdata.io");
            result.put("news", news);
            result.put("count", news.size());
            return JSON.toJSONString(result);
        } catch (Exception e) {
            logger.warn("[DestinationLiveTools] 目的地资讯查询失败，city={}", normalizedCity);
            result.put("available", false);
            result.put("error", "目的地资讯查询暂时不可用，请稍后重试");
            return JSON.toJSONString(result);
        }
    }

    /** 使用 JDK HTTP 客户端执行限时 GET 请求并返回响应正文。 */
    private String sendGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "CorpTripAgent/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("外部查询服务返回 HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** 解析可选 ISO 日期；空值表示查询默认天气范围。 */
    private LocalDate parseDate(String date) {
        return date == null || date.isBlank()
                ? null
                : LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** 从 wttr.in 描述数组读取首个天气说明。 */
    private String firstDescription(JSONArray descriptions) {
        return descriptions == null || descriptions.isEmpty()
                ? ""
                : descriptions.getJSONObject(0).getString("value");
    }

    /** 将资讯主题代码转换为中文检索词。 */
    private String topicKeyword(String topic) {
        if (topic == null || topic.isBlank()) {
            return "出行资讯";
        }
        return switch (topic.trim().toLowerCase()) {
            case "traffic" -> "交通";
            case "event" -> "活动 展会 赛事";
            case "safety" -> "安全 突发事件";
            case "flight" -> "航班 机场";
            case "hotel" -> "酒店 住宿";
            case "policy" -> "政策";
            default -> "出行资讯";
        };
    }
}

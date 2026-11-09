package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.tools.store.TuniuSearchCandidateStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 读取当前用户的途牛搜索结果，并转换为行程规划可使用的候选数据。 */
@Component
public class ItineraryPlanReadTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanReadTools.class);

    private final TuniuSearchCandidateStore candidateStore;

    /**
     * 创建行程候选读取工具。
     *
     * @param candidateStore 按用户隔离的途牛搜索候选存储
     */
    public ItineraryPlanReadTools(TuniuSearchCandidateStore candidateStore) {
        this.candidateStore = candidateStore;
    }

    /**
     * 读取指定行程的去程、返程交通和酒店候选，并统一转换为规划数据结构。
     *
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期，格式为 YYYY-MM-DD
     * @param returnDate 返程日期，格式为 YYYY-MM-DD
     * @param runtimeContext 当前会话上下文，用于隔离用户候选数据
     * @return 包含 transport_options 和 hotel_options 的 JSON 字符串
     */
    @Tool(name = "get_candidates", description = "读取当前用户通过途牛 CLI 搜索并捕获的交通和酒店结果，转换为 transport_options 与 hotel_options 候选列表。按去程日期、返程日期和城市精确匹配；没有搜索结果时返回空列表。必须在途牛搜索后调用，候选字段只来源于真实搜索结果。")
    public String getCandidates(
            @ToolParam(name = "origin", description = "出发城市，例如上海") String origin,
            @ToolParam(name = "destination", description = "目的地城市，例如杭州") String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD") String returnDate,
            AgentRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.getUserId() == null || runtimeContext.getUserId().isBlank()) {
            logger.warn("[TOOL][get_candidates] 缺少当前用户上下文，拒绝读取候选");
            return emptyCandidates("缺少当前用户上下文，无法读取搜索候选。");
        }
        if (isBlank(origin) || isBlank(destination) || isBlank(departureDate) || isBlank(returnDate)) {
            return emptyCandidates("出发城市、目的地城市、去程日期和返程日期均为必填项。");
        }

        String userId = runtimeContext.getUserId();
        logger.info("[TOOL][get_candidates] userId={}, origin={}, destination={}, departureDate={}, returnDate={}",
                userId, origin, destination, departureDate, returnDate);
        Map<String, String> entries = candidateStore.loadByTrip(
                userId, origin.trim(), destination.trim(), departureDate.trim(), returnDate.trim());
        JSONObject candidates = convertEntries(entries, departureDate.trim(), returnDate.trim());
        logger.info("[TOOL][get_candidates] userId={}, transports={}, hotels={}", userId,
                candidates.getJSONArray("transport_options").size(), candidates.getJSONArray("hotel_options").size());
        return candidates.toJSONString();
    }

    /**
     * 按稳定的 Redis field 顺序转换候选，保证同一批搜索结果生成一致的候选 ID。
     *
     * @param entries Redis 中命中的途牛搜索条目
     * @param departureDate 去程日期
     * @param returnDate 返程日期
     * @return 转换后的候选 JSON 对象
     */
    private JSONObject convertEntries(Map<String, String> entries, String departureDate, String returnDate) {
        JSONArray transports = new JSONArray();
        JSONArray hotels = new JSONArray();
        int[] counters = {0, 0};
        List<String> fields = new ArrayList<>(entries.keySet());
        fields.sort(Comparator.naturalOrder());

        // 逐条解析捕获的原始 CLI 业务结果，单条脏数据不影响其他已成功搜索的候选。
        for (String field : fields) {
            try {
                JSONObject entry = JSON.parseObject(entries.get(field));
                if (entry == null || entry.getJSONObject("data") == null) {
                    continue;
                }
                JSONObject data = entry.getJSONObject("data");
                JSONObject args = entry.getJSONObject("args");
                String type = entry.getString("type");
                if (type == null) {
                    continue;
                }
                switch (type.toUpperCase()) {
                    case "FLIGHT" -> convertFlights(data, args, transports, counters);
                    case "TRAIN" -> convertTrains(data, args, transports, counters);
                    case "HOTEL" -> convertHotels(data, args, hotels, counters, departureDate, returnDate);
                    default -> logger.warn("[TOOL][get_candidates] 忽略未知搜索类型: field={}, type={}", field, type);
                }
            } catch (RuntimeException exception) {
                logger.warn("[TOOL][get_candidates] 搜索结果解析失败，跳过 field={}", field, exception);
            }
        }

        JSONObject result = new JSONObject();
        result.put("transport_options", transports);
        result.put("hotel_options", hotels);
        return result;
    }

    /**
     * 将途牛航班条目转换为统一交通候选。
     *
     * @param data 航班搜索业务结果
     * @param args 对应的途牛搜索参数
     * @param target 交通候选列表
     * @param counters 交通和酒店候选 ID 计数器
     */
    private void convertFlights(JSONObject data, JSONObject args, JSONArray target, int[] counters) {
        JSONArray flights = data.getJSONArray("data");
        if (flights == null) {
            return;
        }
        for (int index = 0; index < flights.size(); index++) {
            JSONObject flight = flights.getJSONObject(index);
            if (flight == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("id", "F" + (++counters[0]));
            item.put("type", "flight");
            item.put("departure_time", flight.getString("departureTime"));
            item.put("arrival_time", flight.getString("arrivalTime"));
            item.put("origin", firstText(firstArgument(args, "departureCityName", "departureCity"), flight.getString("departureAirport")));
            item.put("destination", firstText(firstArgument(args, "arrivalCityName", "arrivalCity"), flight.getString("arrivalAirport")));
            item.put("price", round2(parseDouble(flight.getString("basePrice")) + parseDouble(flight.getString("totalTax"))));
            item.put("cabin_class", flight.getString("cabinClass"));
            item.put("is_direct", "直飞".equals(flight.getString("type")));
            item.put("refund_policy", "不可退");
            item.put("carrier", flight.getString("airlineCompany"));
            item.put("code", flight.getString("flightNumber"));
            target.add(item);
        }
    }

    /**
     * 将途牛列车及可售席别展开为统一交通候选。
     *
     * @param data 火车票搜索业务结果
     * @param args 对应的途牛搜索参数
     * @param target 交通候选列表
     * @param counters 交通和酒店候选 ID 计数器
     */
    private void convertTrains(JSONObject data, JSONObject args, JSONArray target, int[] counters) {
        JSONArray trains = data.getJSONArray("data");
        if (trains == null) {
            return;
        }
        String[] seatKeys = {"edz", "ydz", "swz"};
        String[] seatLabels = {"二等座", "一等座", "商务座"};
        for (int index = 0; index < trains.size(); index++) {
            JSONObject train = trains.getJSONObject(index);
            JSONObject prices = train == null ? null : train.getJSONObject("price");
            if (prices == null) {
                continue;
            }
            JSONObject availability = train.getJSONObject("seatAvailable");
            for (int seat = 0; seat < seatKeys.length; seat++) {
                String price = prices.getString(seatKeys[seat] + "Price");
                Integer available = availability == null ? null : availability.getInteger(seatKeys[seat] + "Num");
                if (isBlank(price) || (available != null && available == 0)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("id", "T" + (++counters[0]));
                item.put("type", "train");
                item.put("departure_time", train.getString("departureTime"));
                item.put("arrival_time", train.getString("arrivalTime"));
                item.put("origin", firstText(firstArgument(args, "departureCityName", "departureCity"), train.getString("departStationName")));
                item.put("destination", firstText(firstArgument(args, "arrivalCityName", "arrivalCity", "destinationCity"), train.getString("destStationName")));
                item.put("price", round2(parseDouble(price)));
                item.put("cabin_class", seatLabels[seat]);
                item.put("is_direct", "direct".equals(train.getString("trainType")));
                item.put("refund_policy", "可退");
                item.put("carrier", train.getString("trainNum"));
                item.put("code", train.getString("trainNum"));
                target.add(item);
            }
        }
    }

    /**
     * 将途牛酒店搜索结果转换为统一住宿候选。
     *
     * @param data 酒店搜索业务结果
     * @param args 对应的途牛搜索参数
     * @param target 酒店候选列表
     * @param counters 交通和酒店候选 ID 计数器
     * @param departureDate 行程去程日期
     * @param returnDate 行程返程日期
     */
    private void convertHotels(JSONObject data, JSONObject args, JSONArray target, int[] counters,
                               String departureDate, String returnDate) {
        JSONArray hotels = data.getJSONArray("hotels");
        if (hotels == null) {
            hotels = data.getJSONArray("data");
        }
        if (hotels == null) {
            return;
        }
        int nights = computeNights(args, departureDate, returnDate);
        for (int index = 0; index < hotels.size(); index++) {
            JSONObject hotel = hotels.getJSONObject(index);
            if (hotel == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("id", "H" + (++counters[1]));
            item.put("name", hotel.getString("hotelName"));
            item.put("brand", hotel.getString("brandName"));
            item.put("star_rating", parseStarRating(hotel.getString("starName")));
            item.put("room_type", hotel.getString("roomName"));
            item.put("price_per_night", parseDouble(hotel.getString("lowestPrice"))); // 每晚价格
            item.put("nights", nights);
            item.put("distance_to_dest_km", 0);
            String meal = hotel.getString("meal");
            item.put("breakfast_included", meal != null && meal.contains("早餐"));
            item.put("cancel_policy", hotel.getString("refund"));
            target.add(item);
        }
    }

    /**
     * 根据酒店搜索参数或行程日期计算住宿晚数。
     *
     * @param args 酒店搜索参数
     * @param departureDate 行程去程日期
     * @param returnDate 行程返程日期
     * @return 至少为一晚的住宿晚数
     */
    private int computeNights(JSONObject args, String departureDate, String returnDate) {
        String checkIn = firstArgument(args, "checkInDate", "checkIn");
        String checkOut = firstArgument(args, "checkOutDate", "checkOut");
        try {
            LocalDate start = LocalDate.parse(checkIn == null ? departureDate : checkIn);
            LocalDate end = LocalDate.parse(checkOut == null ? returnDate : checkOut);
            return (int) Math.max(1, ChronoUnit.DAYS.between(start, end));
        } catch (RuntimeException exception) {
            // 日期不符合标准格式时保留有效默认值，避免候选读取因单条参数格式异常而失败。
            return 1;
        }
    }

    /**
     * 将途牛酒店等级映射为规划使用的星级数值。
     *
     * @param starName 途牛返回的酒店档次
     * @return 归一化后的星级
     */
    private int parseStarRating(String starName) {
        if (starName == null) return 3;
        if (starName.contains("豪华")) return 5;
        if (starName.contains("高档")) return 4;
        if (starName.contains("经济")) return 2;
        return 3;
    }

    /**
     * 按顺序返回对象字段中的首个非空值。
     *
     * @param object 搜索参数对象，可以为空
     * @param keys 参数字段名称
     * @return 首个非空文本；没有可用值时返回 null
     */
    private String firstText(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (int index = 0; index < keys.length; index++) {
            String value = object.getString(keys[index]);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 从搜索参数中读取第一个非空字段。
     *
     * @param object 搜索参数对象
     * @param keys 可尝试的字段名称
     * @return 首个非空参数值；对象为空或字段均为空时返回 null
     */
    private String firstArgument(JSONObject object, String... keys) {
        return firstText(object, keys);
    }

    /**
     * 返回两个文本中的首个非空值。
     *
     * @param values 候选文本
     * @return 首个非空文本；没有可用值时返回 null
     */
    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 将字符串安全转换为金额数值。
     *
     * @param value 原始数值文本
     * @return 可解析数值；无效文本按 0 处理
     */
    private double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * 将金额四舍五入到分。
     *
     * @param value 原始金额
     * @return 保留两位小数的金额
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待检查文本
     * @return 为空或只包含空白字符时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 构造空候选响应；参数错误时附带错误说明。
     *
     * @param error 错误原因，可为空
     * @return 保持候选字段结构的 JSON 字符串
     */
    private String emptyCandidates(String error) {
        JSONObject result = new JSONObject();
        result.put("transport_options", new JSONArray());
        result.put("hotel_options", new JSONArray());
        if (error != null) {
            result.put("error", error);
        }
        return result.toJSONString();
    }
}

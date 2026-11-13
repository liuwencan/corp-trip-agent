package cn.lwccxy.trip.agent.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** 根据显式城市对配置和城市等级估算两地之间的最短交通衔接时间。 */
@Service
public class CityTransitTimeService {

    private static final Logger logger = LoggerFactory.getLogger(CityTransitTimeService.class);
    private static final String CONFIG_PREFIX = "corp-trip.policy.";
    private static final int SAME_CITY_MINUTES = 0;
    private static final int TIER_ONE_MINUTES = 270;
    private static final int TIER_TWO_MINUTES = 300;
    private static final int OTHER_CITY_MINUTES = 360;

    /** 一线城市名称列表。 */
    private List<String> tier1Cities = new ArrayList<>();

    /** 新一线城市名称列表。 */
    private List<String> newTier1Cities = new ArrayList<>();

    /** 二线城市名称列表。 */
    private List<String> tier2Cities = new ArrayList<>();

    /** 城市对显式交通时长，单位为分钟。 */
    private Map<String, Integer> cityPairMinutes = new HashMap<>();

    private final Map<String, Integer> normalizedCityPairMinutes = new HashMap<>();

    /** 在应用启动时加载 YAML 配置，并登记城市对的正向和反向映射。 */
    @PostConstruct
    public void initialize() {
        // 1. yml配置解析加载到内存
        loadCityTierConfig();
        normalizedCityPairMinutes.clear();
        if (cityPairMinutes == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : cityPairMinutes.entrySet()) {
            String[] cities = splitCityPair(entry.getKey());
            Integer minutes = entry.getValue();
            if (cities == null || minutes == null || minutes <= 0) {
                logger.warn("忽略无效城市交通时长配置，key={}, value={}", entry.getKey(), minutes);
                continue;
            }
            normalizedCityPairMinutes.put(pairKey(cities[0], cities[1]), minutes);
            normalizedCityPairMinutes.put(pairKey(cities[1], cities[0]), minutes);
        }
    }

    /** 读取城市等级和显式城市对配置；配置缺失时保留空列表并使用估算默认值。 */
    private void loadCityTierConfig() {
        YamlPropertiesFactoryBean yamlFactory = new YamlPropertiesFactoryBean();
        yamlFactory.setResources(new ClassPathResource("config/city-tier.yml"));
        Properties properties;
        try {
            properties = yamlFactory.getObject();
        } catch (Exception exception) {
            logger.error("读取 config/city-tier.yml 失败，城市等级估算将使用未知城市默认时长", exception);
            return;
        }
        if (properties == null) {
            logger.warn("config/city-tier.yml 未提供配置，城市等级估算将使用默认时长");
            return;
        }

        tier1Cities = readIndexedValues(properties, "tier1-cities");
        newTier1Cities = readIndexedValues(properties, "new-tier1-cities");
        tier2Cities = readIndexedValues(properties, "tier2-cities");
        cityPairMinutes = readCityPairValues(properties);
    }

    /** 从 YAML 展平属性中读取指定城市等级列表并按索引顺序排列。 */
    private List<String> readIndexedValues(Properties properties, String propertyName) {
        String prefix = CONFIG_PREFIX + propertyName + "[";
        return properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix) && key.endsWith("]"))
                .sorted(Comparator.comparingInt(key -> indexedValue(key, prefix)))
                .map(properties::getProperty)
                .toList();
    }

    /** 从展平属性名中提取列表项索引，无法解析时排到列表末尾。 */
    private int indexedValue(String key, String prefix) {
        try {
            return Integer.parseInt(key.substring(prefix.length(), key.length() - 1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    /** 从 YAML 展平属性中读取城市对交通时长映射。 */
    private Map<String, Integer> readCityPairValues(Properties properties) {
        String prefix = CONFIG_PREFIX + "city-pair-minutes.";
        Map<String, Integer> values = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String pair = key.substring(prefix.length());
            if (pair.startsWith("[") && pair.endsWith("]")) {
                pair = pair.substring(1, pair.length() - 1);
            }
            try {
                values.put(pair, Integer.parseInt(properties.getProperty(key)));
            } catch (NumberFormatException exception) {
                logger.warn("忽略无效城市交通时长配置，key={}, value={}", pair, properties.getProperty(key));
            }
        }
        return values;
    }

    /** 估算两个城市之间的最短交通衔接时间，返回值单位为分钟。 */
    public int estimateMinutes(String fromCity, String toCity) {
        String from = normalizeCity(fromCity);
        String to = normalizeCity(toCity);
        if (from.isEmpty() || to.isEmpty()) {
            // 城市信息缺失时采用未知城市的保守时长，避免把缺失数据误判为同城。
            logger.warn("城市名称为空，按其他城市交通时长 {} 分钟估算", OTHER_CITY_MINUTES);
            return OTHER_CITY_MINUTES;
        }
        if (from.equals(to)) {
            return SAME_CITY_MINUTES;
        }

        Integer configuredMinutes = normalizedCityPairMinutes.get(pairKey(from, to));
        if (configuredMinutes != null) {
            return configuredMinutes;
        }
        return estimateByCityTier(from, to);
    }

    /**
     * 将目的地映射为差旅政策表使用的城市等级。
     *
     * @param city 目的地城市
     * @return 一线、新一线或其他
     */
    public String resolvePolicyCityTier(String city) {
        String normalizedCity = normalizeCity(city);
        if (normalizedCity.isEmpty()) {
            return "其他";
        }
        if (containsCity(tier1Cities, normalizedCity)) {
            return "一线";
        }
        if (containsCity(newTier1Cities, normalizedCity)) {
            return "新一线";
        }
        return "其他";
    }

    /** 按两地中較低的城市等级返回保守估算时长。 */
    private int estimateByCityTier(String fromCity, String toCity) {
        int fromRank = cityTierRank(fromCity);
        int toRank = cityTierRank(toCity);
        int lowerTierRank = Math.max(fromRank, toRank);
        if (lowerTierRank >= 3) {
            return OTHER_CITY_MINUTES;
        }
        if (lowerTierRank == 2) {
            return TIER_TWO_MINUTES;
        }
        return TIER_ONE_MINUTES;
    }

    /** 返回城市等级排序，一线最高，新一线次之，二线再次，未配置城市最低。 */
    private int cityTierRank(String city) {
        if (containsCity(tier1Cities, city)) {
            return 0;
        }
        if (containsCity(newTier1Cities, city)) {
            return 1;
        }
        if (containsCity(tier2Cities, city)) {
            return 2;
        }
        return 3;
    }

    /** 判断城市列表中是否包含归一化后相同的城市名称。 */
    private boolean containsCity(List<String> cities, String target) {
        return cities != null && cities.stream()
                .map(CityTransitTimeService::normalizeCity)
                .anyMatch(target::equals);
    }

    /** 解析形如“城市A-城市B”或“[城市A-城市B]”的城市对配置键。 */
    private String[] splitCityPair(String configuredPair) {
        if (configuredPair == null || configuredPair.isBlank()) {
            return null;
        }
        String normalizedPair = configuredPair.trim();
        if (normalizedPair.startsWith("[") && normalizedPair.endsWith("]")) {
            normalizedPair = normalizedPair.substring(1, normalizedPair.length() - 1);
        }
        int separator = normalizedPair.indexOf('-');
        if (separator <= 0 || separator >= normalizedPair.length() - 1) {
            return null;
        }
        String first = normalizeCity(normalizedPair.substring(0, separator));
        String second = normalizeCity(normalizedPair.substring(separator + 1));
        return first.isEmpty() || second.isEmpty() ? null : new String[]{first, second};
    }

    /** 归一化城市名，忽略首尾空白、大小写和末尾“市”字。 */
    private static String normalizeCity(String city) {
        if (city == null) {
            return "";
        }
        String normalized = city.trim();
        if (normalized.endsWith("市") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** 使用稳定分隔符构造方向相关的城市对查询键。 */
    private static String pairKey(String fromCity, String toCity) {
        return fromCity + "->" + toCity;
    }
}

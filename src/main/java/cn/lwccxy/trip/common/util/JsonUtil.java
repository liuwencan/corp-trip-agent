package cn.lwccxy.trip.common.util;

import com.alibaba.fastjson2.JSON;

import java.util.Map;

/**
 * 提供通用 JSON 转换操作。
 */
public final class JsonUtil {

    /**
     * 禁止实例化 JSON 工具类。
     */
    private JsonUtil() {
    }

    /**
     * 将 Map 转换为 JSON 字符串。
     *
     * @param map 待转换的数据
     * @return 对应的 JSON 字符串
     */
    public static String toJson(Map<?, ?> map) {
        // 交由项目已使用的 Fastjson2 统一处理 JSON 序列化。
        return JSON.toJSONString(map);
    }
}

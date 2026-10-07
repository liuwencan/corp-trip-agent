package cn.lwccxy.trip.system.config.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统配置常量类
 *
 * @Author 1024创新实验室-主任: 卓大
 * @Date 2022-03-14 20:46:27
 * @Wechat zhuoda1024
 * @Email lab1024@163.com
 * @Copyright  <a href="https://1024lab.net">1024创新实验室</a>
 */
@Getter
@AllArgsConstructor
public enum ConfigKeyEnum{

    /**
     * 万能密码
     */
    SUPER_PASSWORD("super_password", "万能密码"),

    LEVEL3_PROTECT_CONFIG("level3_protect_config", "三级等保配置"),

    IS_RATE_LIMIT("isRateLimit", "限流降级开关")
    ;

    private final String value;

    private final String desc;
}

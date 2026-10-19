package cn.lwccxy.trip.system.user.domain.vo;

import lombok.Data;

@Data
public class LoginVO {
    private String tokenName;
    /** 请求头中 Token 的前缀；未配置时为空，客户端直接发送 tokenValue。 */
    private String tokenPrefix;
    private String tokenValue;
    private long expiresIn;
    private UserVO user;
}

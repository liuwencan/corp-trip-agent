package cn.lwccxy.trip.system.user.domain.vo;

import lombok.Data;

@Data
public class LoginVO {
    private String tokenName;
    private String tokenValue;
    private long expiresIn;
    private UserVO user;
}

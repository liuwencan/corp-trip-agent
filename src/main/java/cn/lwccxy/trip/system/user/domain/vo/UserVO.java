package cn.lwccxy.trip.system.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String role;
    private Boolean mustChangePassword;
    private String nickName;
    private String avatar;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

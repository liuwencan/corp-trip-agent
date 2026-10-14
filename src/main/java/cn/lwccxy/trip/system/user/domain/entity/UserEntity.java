package cn.lwccxy.trip.system.user.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class UserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @lombok.ToString.Exclude
    private String passwordHash;
    private String role;
    private Boolean mustChangePassword;
    private Long credentialVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

package cn.lwccxy.trip.system.user.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_password_reset_log")
public class PasswordResetLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long targetUserId;
    private Long operatorId;
    private String reason;
    private LocalDateTime createTime;
}

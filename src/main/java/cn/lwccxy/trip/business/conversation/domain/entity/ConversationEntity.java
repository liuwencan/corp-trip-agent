package cn.lwccxy.trip.business.conversation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String status;
    private String runtimeSessionId;
    private Integer messageCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

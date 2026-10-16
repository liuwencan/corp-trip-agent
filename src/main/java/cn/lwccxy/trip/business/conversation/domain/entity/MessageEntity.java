package cn.lwccxy.trip.business.conversation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class MessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long userId;
    private String role;
    @ToString.Exclude
    private String content;
    private String modelName;
    private Long createdBy;
    private LocalDateTime createTime;
    private String feedback;
    private LocalDateTime feedbackAt;
}

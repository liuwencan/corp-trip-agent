package cn.lwccxy.trip.business.conversation.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageVO {
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private String modelName;
    private LocalDateTime createTime;
    private String feedback;
    private LocalDateTime feedbackAt;
}

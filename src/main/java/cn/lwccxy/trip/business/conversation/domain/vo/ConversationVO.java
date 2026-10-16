package cn.lwccxy.trip.business.conversation.domain.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationVO {
    private Long id;
    private String title;
    private String status;
    private Integer messageCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

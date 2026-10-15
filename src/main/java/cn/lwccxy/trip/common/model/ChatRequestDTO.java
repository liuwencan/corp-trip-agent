package cn.lwccxy.trip.common.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequestDTO {

    @NotBlank(message = "用户问题不能为空")
    private String question;

    @NotBlank(message = "会话ID不能为空")
    private String sessionId;
}

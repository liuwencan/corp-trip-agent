package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.model.ChatRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    private final CurrentUser currentUser;
    private final ChatAgentPipeline pipeline;

    /** 为当前认证用户的会话启动对话流程。 */
    @PostMapping("/chat")
    public SseEmitter send(@Valid @RequestBody ChatRequestDTO form) {
        return pipeline.execute(currentUser.get().getId(), form.getConversationId(), form.getQuestion());
    }
}

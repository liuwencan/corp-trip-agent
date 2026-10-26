package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.business.chat.domain.form.ChatResumeForm;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.model.ChatRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    private final CurrentUser currentUser;
    private final ChatAgentPipeline pipeline;

    /** 为当前认证用户的会话启动对话流程。 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatRequestDTO form) {
        return pipeline.execute(currentUser.get().getId(), form.getConversationId(), form.getQuestion());
    }

//    /** 接收用户对智能体澄清问题的回答，并恢复同一会话的流式对话。 */
//    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public SseEmitter resume(@Valid @RequestBody ChatResumeForm form) {
//        return pipeline.(currentUser.get().getId(), form.getConversationId(), form.getToolCallId(), form.getAnswer());
//    }
}

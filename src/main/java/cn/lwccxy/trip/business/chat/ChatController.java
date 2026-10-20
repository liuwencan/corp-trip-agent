package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.model.ChatRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    private final CurrentUser currentUser;
    private final ChatAgentPipeline pipeline;

    /** 为当前认证用户的会话启动对话流程。 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> send(@Valid @RequestBody ChatRequestDTO form) {
        return pipeline.execute(currentUser.get().getId(), form.getConversationId(), form.getQuestion());
    }
}

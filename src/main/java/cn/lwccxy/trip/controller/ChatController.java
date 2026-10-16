package cn.lwccxy.trip.controller;

import cn.lwccxy.trip.business.conversation.service.ConversationChatService;
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
    private final ConversationChatService chat;

    @PostMapping("/chat")
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO form) {
        return chat.send(currentUser.get().getId(), form.getConversationId(), form.getQuestion());
    }
}

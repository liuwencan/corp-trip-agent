package cn.lwccxy.trip.business.conversation.controller;

import cn.lwccxy.trip.business.conversation.domain.form.*;
import cn.lwccxy.trip.business.conversation.domain.vo.*;
import cn.lwccxy.trip.business.conversation.service.*;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final CurrentUser currentUser;
    private final ConversationService service;
    private final ConversationChatService chat;

    @PostMapping
    public Response<ConversationVO> create(@Valid @RequestBody CreateConversationForm form) {
        return Response.success(service.create(currentUser.get().getId(), form));
    }
    @GetMapping
    public PageResponse<ConversationVO> list(@Valid @ModelAttribute ConversationQueryForm form) {
        return service.list(currentUser.get().getId(), form);
    }
    @GetMapping("/{conversationId}")
    public Response<ConversationVO> detail(@Positive @PathVariable Long conversationId) {
        return Response.success(service.detail(currentUser.get().getId(), conversationId));
    }
    @PutMapping("/{conversationId}")
    public Response<ConversationVO> update(@Positive @PathVariable Long conversationId,
                                           @Valid @RequestBody UpdateConversationForm form) {
        return Response.success(service.update(currentUser.get().getId(), conversationId, form));
    }
    @GetMapping("/{conversationId}/messages")
    public PageResponse<MessageVO> history(@Positive @PathVariable Long conversationId,
                                           @Valid @ModelAttribute HistoryPageForm form) {
        return service.history(currentUser.get().getId(), conversationId, form);
    }
    @PostMapping("/{conversationId}/messages")
    public SseEmitter send(@Positive @PathVariable Long conversationId, @Valid @RequestBody SendMessageForm form) {
        return chat.send(currentUser.get().getId(), conversationId, form.getContent());
    }
    @PutMapping("/{conversationId}/messages/{messageId}/feedback")
    public Response<MessageVO> feedback(@Positive @PathVariable Long conversationId,
                                        @Positive @PathVariable Long messageId,
                                        @Valid @RequestBody MessageFeedbackForm form) {
        return Response.success(service.feedback(currentUser.get().getId(), conversationId, messageId, form));
    }
}

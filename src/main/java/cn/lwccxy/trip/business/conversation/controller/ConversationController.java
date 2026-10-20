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

@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final CurrentUser currentUser;
    private final ConversationService service;

    /** 创建当前用户所属的会话，不调用对话编排流程。 */
    @PostMapping
    public Response<ConversationVO> create(@Valid @RequestBody CreateConversationForm form) {
        return Response.success(service.create(currentUser.get().getId(), form));
    }
    /** 分页查询当前认证用户的会话列表。 */
    @GetMapping
    public PageResponse<ConversationVO> list(@Valid @ModelAttribute ConversationQueryForm form) {
        return service.list(currentUser.get().getId(), form);
    }
    /** 查询当前用户所属的会话详情。 */
    @GetMapping("/{conversationId}")
    public Response<ConversationVO> detail(@Positive @PathVariable Long conversationId) {
        return Response.success(service.detail(currentUser.get().getId(), conversationId));
    }
    /** 更新当前用户所属会话的标题或生命周期状态。 */
    @PutMapping("/{conversationId}")
    public Response<ConversationVO> update(@Positive @PathVariable Long conversationId,
                                           @Valid @RequestBody UpdateConversationForm form) {
        return Response.success(service.update(currentUser.get().getId(), conversationId, form));
    }
    /** 分页查询当前用户所属会话的消息历史。 */
    @GetMapping("/{conversationId}/messages")
    public PageResponse<MessageVO> history(@Positive @PathVariable Long conversationId,
                                           @Valid @ModelAttribute HistoryPageForm form) {
        return service.history(currentUser.get().getId(), conversationId, form);
    }
    /** 更新 assistant 消息反馈，不调用对话编排流程。 */
    @PutMapping("/{conversationId}/messages/{messageId}/feedback")
    public Response<MessageVO> feedback(@Positive @PathVariable Long conversationId,
                                        @Positive @PathVariable Long messageId,
                                        @Valid @RequestBody MessageFeedbackForm form) {
        return Response.success(service.feedback(currentUser.get().getId(), conversationId, messageId, form));
    }
}

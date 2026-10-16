package cn.lwccxy.trip.business.conversation.service;

import cn.lwccxy.trip.business.conversation.domain.entity.*;
import cn.lwccxy.trip.business.conversation.domain.form.*;
import cn.lwccxy.trip.business.conversation.domain.vo.*;
import cn.lwccxy.trip.business.conversation.enums.*;
import cn.lwccxy.trip.business.conversation.mapper.*;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationMapper conversations;
    private final MessageMapper messages;
    private final TransactionTemplate transactions;
    private final ConversationCoordinator coordinator;

    public ConversationVO create(Long userId, CreateConversationForm form) {
        return transactions.execute(tx -> {
            ConversationEntity entity = new ConversationEntity();
            entity.setUserId(userId);
            entity.setTitle(form.getTitle() == null ? "新会话" : form.getTitle());
            entity.setStatus(ConversationStatus.ACTIVE.name());
            entity.setRuntimeSessionId(UUID.randomUUID().toString());
            entity.setMessageCount(0);
            conversations.insert(entity);
            return conversationVO(owned(userId, entity.getId()));
        });
    }

    public ConversationEntity owned(Long userId, Long id) {
        positive(id);
        return require(conversations.owned(userId, id));
    }

    public ConversationVO detail(Long userId, Long id) { return conversationVO(owned(userId, id)); }

    public PageResponse<ConversationVO> list(Long userId, ConversationQueryForm form) {
        page(form);
        String status = form.getStatus();
        if (!"ALL".equals(status)) enumeration(ConversationStatus.class, status);
        return PageResponse.success(conversations.pageOwned(userId, status, offset(form), form.getPageSize())
                .stream().map(this::conversationVO).toList(), form.getPageNo(),
                conversations.countOwned(userId, status), form.getPageSize());
    }

    public ConversationVO update(Long userId, Long id, UpdateConversationForm form) {
        owned(userId, id);
        try (var lease = coordinator.acquire(id, false)) {
            return transactions.execute(tx -> {
                ConversationEntity old = require(conversations.lockOwned(userId, id));
                String title = form.getTitle() == null ? old.getTitle() : form.getTitle();
                String status = form.getStatus() == null ? old.getStatus() : form.getStatus();
                enumeration(ConversationStatus.class, status);
                if (!status.equals(old.getStatus()) && !("ACTIVE".equals(old.getStatus())
                        && ("ARCHIVED".equals(status) || "CLOSED".equals(status)))
                        && !("ARCHIVED".equals(old.getStatus()) && "CLOSED".equals(status))) {
                    throw new BizException(ConversationErrorCode.CONVERSATION_STATE_INVALID);
                }
                if (!title.equals(old.getTitle()) || !status.equals(old.getStatus())) {
                    changed(conversations.change(userId, id, title, status));
                }
                return conversationVO(owned(userId, id));
            });
        }
    }

    public PageResponse<MessageVO> history(Long userId, Long id, HistoryPageForm form) {
        owned(userId, id);
        page(form);
        return PageResponse.success(messages.pageOwned(userId, id, offset(form), form.getPageSize())
                .stream().map(this::messageVO).toList(), form.getPageNo(),
                messages.countOwned(userId, id), form.getPageSize());
    }

    public MessageVO append(Long userId, Long id, MessageRole role, String content, String modelName, String traceId) {
        positive(id);
        if (role == null || content == null || content.isBlank() || (modelName != null && modelName.length() > 128)
                || (traceId != null && traceId.length() > 64)) invalid();
        return transactions.execute(tx -> {
            ConversationEntity conversation = require(conversations.lockOwned(userId, id));
            if (!"ACTIVE".equals(conversation.getStatus())) throw new BizException(ConversationErrorCode.CONVERSATION_STATE_INVALID);
            MessageEntity message = new MessageEntity();
            message.setConversationId(id);
            message.setUserId(userId);
            message.setRole(role.name());
            message.setContent(content);
            message.setModelName(modelName);
            message.setCreatedBy(userId);
            changed(messages.insert(message));
            changed(conversations.appended(userId, id, traceId));
            return messageVO(require(messages.lockOwned(userId, id, message.getId())));
        });
    }

    public MessageVO feedback(Long userId, Long id, Long messageId, MessageFeedbackForm form) {
        owned(userId, id);
        positive(messageId);
        if (!form.isFeedbackPresent()) invalid();
        if (form.getFeedback() != null) enumeration(MessageFeedback.class, form.getFeedback());
        return transactions.execute(tx -> {
            MessageEntity message = require(messages.lockOwned(userId, id, messageId));
            if (!MessageRole.ASSISTANT.name().equals(message.getRole())) invalid();
            if (!Objects.equals(message.getFeedback(), form.getFeedback())) {
                changed(messages.feedback(userId, id, messageId, form.getFeedback()));
                message = require(messages.lockOwned(userId, id, messageId));
            }
            return messageVO(message);
        });
    }

    private ConversationVO conversationVO(ConversationEntity entity) {
        ConversationVO vo = new ConversationVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private MessageVO messageVO(MessageEntity entity) {
        MessageVO vo = new MessageVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private static <T> T require(T value) {
        if (value == null) throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
        return value;
    }

    private static void changed(int rows) { if (rows != 1) throw new IllegalStateException("Persistence failed"); }
    private static void invalid() { throw new BizException(ConversationErrorCode.INVALID_PARAMETER); }
    private static void positive(Long id) { if (id == null || id <= 0) invalid(); }
    private static void page(HistoryPageForm form) {
        if (form.getPageNo() == null || form.getPageNo() < 1 || form.getPageNo() > Integer.MAX_VALUE
                || form.getPageSize() == null || form.getPageSize() < 1 || form.getPageSize() > 100) invalid();
    }
    private static long offset(HistoryPageForm form) { return (form.getPageNo() - 1) * form.getPageSize(); }
    private static <E extends Enum<E>> void enumeration(Class<E> type, String value) {
        try { Enum.valueOf(type, value); } catch (IllegalArgumentException | NullPointerException ex) { invalid(); }
    }
}

package cn.lwccxy.trip.business.intent.service;

import cn.lwccxy.trip.agent.enums.IntentType;
import cn.lwccxy.trip.business.intent.domain.entity.IntentUtteranceEntity;
import cn.lwccxy.trip.business.intent.domain.form.IntentUtteranceQueryForm;
import cn.lwccxy.trip.business.intent.domain.form.IntentUtteranceSaveForm;
import cn.lwccxy.trip.business.intent.domain.vo.IntentUtteranceVO;
import cn.lwccxy.trip.business.intent.enums.IntentUtteranceStatus;
import cn.lwccxy.trip.business.intent.mapper.IntentUtteranceMapper;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 管理意图口语化表达的业务服务。 */
@Service
@RequiredArgsConstructor
public class IntentUtteranceService {

    private final IntentUtteranceMapper mapper;

    /**
     * 分页查询意图表达，支持意图编码、状态和文本关键词筛选。
     *
     * @param form 分页和筛选条件
     * @return 分页查询结果
     */
    public PageResponse<IntentUtteranceVO> query(IntentUtteranceQueryForm form) {
        String intentCode = normalizeIntentCode(form.getIntentCode());
        if (intentCode != null && !isSupportedIntent(intentCode)) {
            return failedPage("INVALID_INTENT_CODE", "目标意图编码无效");
        }

        // 由数据库完成条件筛选和分页，避免后台管理接口将整张表达表加载到内存。
        LambdaQueryWrapper<IntentUtteranceEntity> query = new LambdaQueryWrapper<>();
        if (intentCode != null) {
            query.eq(IntentUtteranceEntity::getIntentCode, intentCode);
        }
        if (form.getStatus() != null && !"ALL".equals(form.getStatus())) {
            query.eq(IntentUtteranceEntity::getStatus, form.getStatus());
        }
        if (form.getKeyword() != null && !form.getKeyword().isBlank()) {
            query.like(IntentUtteranceEntity::getUtteranceText, form.getKeyword().strip());
        }
        query.orderByDesc(IntentUtteranceEntity::getUpdateTime)
                .orderByDesc(IntentUtteranceEntity::getId);

        Page<IntentUtteranceEntity> page = mapper.selectPage(
                new Page<>(form.getPageNo(), form.getPageSize()), query);
        return PageResponse.success(page.getRecords().stream().map(this::toVO).toList(),
                page.getCurrent(), page.getTotal(), page.getSize());
    }

    /**
     * 根据主键查询意图表达。
     *
     * @param id 意图表达主键
     * @return 查询到的记录或不存在错误
     */
    public Response<IntentUtteranceVO> get(Long id) {
        IntentUtteranceEntity entity = mapper.selectById(id);
        return entity == null
                ? Response.fail("INTENT_UTTERANCE_NOT_FOUND", "意图表达不存在")
                : Response.success(toVO(entity));
    }

    /**
     * 新增意图口语化表达。
     *
     * @param form 新增参数
     * @return 新增记录或参数冲突错误
     */
    public Response<IntentUtteranceVO> create(IntentUtteranceSaveForm form) {
        IntentUtteranceEntity entity = toEntity(form);
        if (!isSupportedIntent(entity.getIntentCode())) {
            return Response.fail("INVALID_INTENT_CODE", "目标意图编码无效");
        }
        if (!isValidStatus(entity.getStatus())) {
            return Response.fail("INVALID_INTENT_UTTERANCE_STATUS", "状态值无效");
        }
        if (existsDuplicate(entity.getIntentCode(), entity.getUtteranceText(), null)) {
            return Response.fail("INTENT_UTTERANCE_ALREADY_EXISTS", "该意图下已存在相同表达");
        }

        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            // 唯一索引是并发新增时的最终约束，冲突统一转换为可理解的业务响应。
            return Response.fail("INTENT_UTTERANCE_ALREADY_EXISTS", "该意图下已存在相同表达");
        }
        return Response.success(toVO(entity));
    }

    /**
     * 更新意图口语化表达。
     *
     * @param id 意图表达主键
     * @param form 更新参数
     * @return 更新后的记录或业务错误
     */
    public Response<IntentUtteranceVO> update(Long id, IntentUtteranceSaveForm form) {
        IntentUtteranceEntity old = mapper.selectById(id);
        if (old == null) {
            return Response.fail("INTENT_UTTERANCE_NOT_FOUND", "意图表达不存在");
        }

        IntentUtteranceEntity entity = toEntity(form);
        if (!isSupportedIntent(entity.getIntentCode())) {
            return Response.fail("INVALID_INTENT_CODE", "目标意图编码无效");
        }
        if (!isValidStatus(entity.getStatus())) {
            return Response.fail("INVALID_INTENT_UTTERANCE_STATUS", "状态值无效");
        }
        if (existsDuplicate(entity.getIntentCode(), entity.getUtteranceText(), id)) {
            return Response.fail("INTENT_UTTERANCE_ALREADY_EXISTS", "该意图下已存在相同表达");
        }

        // 保留数据库生成的主键和创建时间，只允许管理请求更新可维护字段。
        entity.setId(id);
        entity.setCreateTime(old.getCreateTime());
        try {
            mapper.updateById(entity);
        } catch (DuplicateKeyException exception) {
            return Response.fail("INTENT_UTTERANCE_ALREADY_EXISTS", "该意图下已存在相同表达");
        }
        return Response.success(toVO(mapper.selectById(id)));
    }

    /**
     * 删除意图口语化表达。
     *
     * @param id 意图表达主键
     * @return 删除结果或不存在错误
     */
    public Response<Void> delete(Long id) {
        int rows = mapper.deleteById(id);
        return rows == 0
                ? Response.fail("INTENT_UTTERANCE_NOT_FOUND", "意图表达不存在")
                : Response.success();
    }

    /**
     * 校验目标编码是否对应可维护的具体业务意图。
     *
     * @param intentCode 目标意图编码
     * @return 编码存在且不为 UNKNOWN 时返回 true
     */
    private boolean isSupportedIntent(String intentCode) {
        return Arrays.stream(IntentType.values())
                .anyMatch(intentType -> intentType != IntentType.CHAT
                        && intentType.getTargetIntent().equals(intentCode));
    }

    /**
     * 校验表达记录状态是否为支持的枚举值。
     *
     * @param status 状态字符串
     * @return 状态合法时返回 true
     */
    private boolean isValidStatus(String status) {
        return Arrays.stream(IntentUtteranceStatus.values())
                .anyMatch(value -> value.name().equals(status));
    }

    /**
     * 检查同一意图下是否已有相同文本，更新时排除当前记录。
     *
     * @param intentCode 目标意图编码
     * @param utteranceText 用户表达
     * @param excludeId 更新时需要排除的主键
     * @return 存在重复记录时返回 true
     */
    private boolean existsDuplicate(String intentCode, String utteranceText, Long excludeId) {
        LambdaQueryWrapper<IntentUtteranceEntity> query = new LambdaQueryWrapper<IntentUtteranceEntity>()
                .eq(IntentUtteranceEntity::getIntentCode, intentCode)
                .eq(IntentUtteranceEntity::getUtteranceText, utteranceText);
        if (excludeId != null) {
            query.ne(IntentUtteranceEntity::getId, excludeId);
        }
        return mapper.selectCount(query) > 0;
    }

    /**
     * 将请求参数规范化为数据库实体。
     *
     * @param form 新增或更新请求
     * @return 待持久化实体
     */
    private IntentUtteranceEntity toEntity(IntentUtteranceSaveForm form) {
        IntentUtteranceEntity entity = new IntentUtteranceEntity();
        entity.setIntentCode(normalizeIntentCode(form.getIntentCode()));
        entity.setUtteranceText(form.getUtteranceText().strip());
        entity.setStatus(form.getStatus() == null ? IntentUtteranceStatus.ACTIVE.name() : form.getStatus());
        entity.setRemark(form.getRemark() == null || form.getRemark().isBlank() ? null : form.getRemark().strip());
        return entity;
    }

    /**
     * 将数据库实体转换为接口视图对象。
     *
     * @param entity 数据库实体
     * @return 不暴露持久层类型的接口对象
     */
    private IntentUtteranceVO toVO(IntentUtteranceEntity entity) {
        IntentUtteranceVO vo = new IntentUtteranceVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 统一意图编码格式，避免大小写或外围空格造成无法关联。
     *
     * @param intentCode 原始意图编码
     * @return 大写规范编码；空值返回 null
     */
    private String normalizeIntentCode(String intentCode) {
        return intentCode == null || intentCode.isBlank()
                ? null : intentCode.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * 创建符合分页响应类型的失败结果。
     *
     * @param errorCode 错误码
     * @param message 面向管理员的错误说明
     * @return success=false 的分页响应
     */
    private PageResponse<IntentUtteranceVO> failedPage(String errorCode, String message) {
        PageResponse<IntentUtteranceVO> response = new PageResponse<>();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setMessage(message);
        return response;
    }
}

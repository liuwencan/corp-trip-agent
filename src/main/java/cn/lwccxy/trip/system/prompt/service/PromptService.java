package cn.lwccxy.trip.system.prompt.service;

import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.prompt.domain.form.PromptOperationForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptQueryForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptRollbackForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptSaveForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptVersionSaveForm;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptReleaseLogVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVersionCompareVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVersionVO;
import cn.lwccxy.trip.system.prompt.domain.vo.PromptVO;
import cn.lwccxy.trip.system.prompt.domain.entity.PromptEntity;
import cn.lwccxy.trip.system.prompt.domain.entity.PromptReleaseLogEntity;
import cn.lwccxy.trip.system.prompt.domain.entity.PromptVersionEntity;
import cn.lwccxy.trip.system.prompt.enums.PromptErrorCode;
import cn.lwccxy.trip.system.prompt.enums.PromptReleaseOperationType;
import cn.lwccxy.trip.system.prompt.enums.PromptReleaseResult;
import cn.lwccxy.trip.system.prompt.enums.PromptStatus;
import cn.lwccxy.trip.system.prompt.enums.PromptVersionStatus;
import cn.lwccxy.trip.system.prompt.mapper.PromptMapper;
import cn.lwccxy.trip.system.prompt.mapper.PromptReleaseLogMapper;
import cn.lwccxy.trip.system.prompt.mapper.PromptVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class PromptService extends ServiceImpl<PromptMapper, PromptEntity> {

    @Resource
    private PromptMapper promptMapper;

    @Resource
    private PromptVersionMapper promptVersionMapper;

    @Resource
    private PromptReleaseLogMapper promptReleaseLogMapper;

    @Resource
    private OperatorIdentityProvider operatorIdentityProvider;

    public PageResponse<PromptVO> query(PromptQueryForm form) {
        Page<PromptEntity> page = new Page<>(form.getPageNo(), form.getPageSize());
        LambdaQueryWrapper<PromptEntity> query = new LambdaQueryWrapper<>();
        if (form.getKeyword() != null && !form.getKeyword().isBlank()) {
            String keyword = form.getKeyword().trim();
            query.like(PromptEntity::getPromptKey, keyword)
                    .or()
                    .like(PromptEntity::getPromptName, keyword)
                    .or()
                    .like(PromptEntity::getAgentCapability, keyword);
        }
        query.orderByDesc(PromptEntity::getUpdateTime)
                .orderByDesc(PromptEntity::getId);
        Page<PromptEntity> result = promptMapper.selectPage(page, query);
        List<PromptVO> data = result.getRecords().stream().map(this::toVO).toList();
        return PageResponse.success(data, result.getCurrent(), result.getTotal(), result.getSize());
    }

    public Response<PromptVO> get(Long id) {
        PromptEntity entity = promptMapper.selectById(id);
        return entity == null
                ? fail(PromptErrorCode.PROMPT_NOT_FOUND)
                : Response.success(toVO(entity));
    }

    @Transactional
    public Response<PromptVO> add(PromptSaveForm form) {
        String operator = operator();
        if (operator == null) {
            return fail(PromptErrorCode.PROMPT_OPERATOR_REQUIRED);
        }
        String key = form.getPromptKey().trim();
        Long count = promptMapper.selectCount(new LambdaQueryWrapper<PromptEntity>()
                .eq(PromptEntity::getPromptKey, key));
        if (count != 0) {
            return fail(PromptErrorCode.PROMPT_ALREADY_EXISTS);
        }
        PromptEntity entity = new PromptEntity();
        entity.setPromptKey(key);
        entity.setPromptName(form.getPromptName().trim());
        entity.setAgentCapability(form.getAgentCapability().trim());
        entity.setDescription(trimToNull(form.getDescription()));
        entity.setStatus(PromptStatus.ACTIVE.name());
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);
        promptMapper.insert(entity);
        return Response.success(toVO(entity));
    }

    public PageResponse<PromptVersionVO> listVersions(Long promptId, long pageNo, long pageSize) {
        if (promptMapper.selectById(promptId) == null) {
            return failPage(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        Page<PromptVersionEntity> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<PromptVersionEntity> query = new LambdaQueryWrapper<>();
        query.eq(PromptVersionEntity::getPromptId, promptId)
                .orderByDesc(PromptVersionEntity::getVersionNo);
        Page<PromptVersionEntity> result = promptVersionMapper.selectPage(page, query);
        Long currentVersionId = promptMapper.selectById(promptId).getCurrentVersionId();
        List<PromptVersionVO> data = result.getRecords().stream()
                .map(version -> toVO(version, currentVersionId))
                .toList();
        return PageResponse.success(data, result.getCurrent(), result.getTotal(), result.getSize());
    }

    public Response<PromptVersionVO> getVersion(Long promptId, Long versionId) {
        PromptVersionEntity version = findVersion(promptId, versionId);
        if (version == null) {
            return fail(PromptErrorCode.PROMPT_VERSION_NOT_FOUND);
        }
        PromptEntity prompt = promptMapper.selectById(promptId);
        if (prompt == null) {
            return fail(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        return Response.success(toVO(version, prompt.getCurrentVersionId()));
    }

    public Response<PromptVersionCompareVO> compareVersions(Long promptId, Long leftVersionId, Long rightVersionId) {
        PromptVersionEntity left = findVersion(promptId, leftVersionId);
        PromptVersionEntity right = findVersion(promptId, rightVersionId);
        if (left == null || right == null) {
            return fail(PromptErrorCode.PROMPT_VERSION_NOT_FOUND);
        }
        PromptVersionCompareVO vo = new PromptVersionCompareVO();
        vo.setPromptId(promptId);
        vo.setLeftVersionId(left.getId());
        vo.setLeftVersionNo(left.getVersionNo());
        vo.setRightVersionId(right.getId());
        vo.setRightVersionNo(right.getVersionNo());
        vo.setSystemPromptChanged(!same(left.getSystemPrompt(), right.getSystemPrompt()));
        vo.setUserTemplateChanged(!same(left.getUserTemplate(), right.getUserTemplate()));
        vo.setOutputSchemaChanged(!same(left.getOutputSchema(), right.getOutputSchema()));
        vo.setLeftSystemPrompt(left.getSystemPrompt());
        vo.setRightSystemPrompt(right.getSystemPrompt());
        vo.setLeftUserTemplate(left.getUserTemplate());
        vo.setRightUserTemplate(right.getUserTemplate());
        vo.setLeftOutputSchema(left.getOutputSchema());
        vo.setRightOutputSchema(right.getOutputSchema());
        return Response.success(vo);
    }

    @Transactional
    public Response<PromptVersionVO> addVersion(Long promptId, PromptVersionSaveForm form) {
        if (form == null || form.getVersionNo() == null || form.getVersionNo() < 1
                || form.getSystemPrompt() == null || form.getSystemPrompt().isBlank()
                || form.getChangeReason() == null || form.getChangeReason().isBlank()) {
            return fail(form == null || form.getChangeReason() == null || form.getChangeReason().isBlank()
                    ? PromptErrorCode.PROMPT_OPERATION_REASON_REQUIRED
                    : PromptErrorCode.PROMPT_VERSION_CONTENT_INVALID);
        }
        String operator = operator();
        if (operator == null) {
            return fail(PromptErrorCode.PROMPT_OPERATOR_REQUIRED);
        }
        PromptEntity prompt = promptMapper.selectByIdForUpdate(promptId);
        if (prompt == null) {
            return fail(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        Integer maxVersionNo = promptVersionMapper.selectMaxVersionNo(promptId);
        if (form.getVersionNo() <= maxVersionNo) {
            return fail(PromptErrorCode.PROMPT_VERSION_NOT_INCREASING);
        }
        PromptVersionEntity version = new PromptVersionEntity();
        version.setPromptId(promptId);
        version.setVersionNo(form.getVersionNo());
        version.setSystemPrompt(form.getSystemPrompt());
        version.setUserTemplate(form.getUserTemplate());
        version.setOutputSchema(form.getOutputSchema());
        version.setContentHash(contentHash(form));
        version.setVersionStatus(PromptVersionStatus.DRAFT.name());
        version.setChangeReason(form.getChangeReason().trim());
        version.setCreatedBy(operator);
        try {
            promptVersionMapper.insert(version);
        } catch (DuplicateKeyException ex) {
            log.warn("Prompt version conflict: promptId={}, versionNo={}", promptId, form.getVersionNo());
            return fail(PromptErrorCode.PROMPT_VERSION_CONFLICT);
        }
        return Response.success(toVO(version, prompt.getCurrentVersionId()));
    }

    @Transactional
    public Response<PromptVersionVO> publish(Long promptId, Long versionId, PromptOperationForm form) {
        if (!hasReason(form)) {
            return fail(PromptErrorCode.PROMPT_OPERATION_REASON_REQUIRED);
        }
        String operator = operator();
        if (operator == null) {
            return fail(PromptErrorCode.PROMPT_OPERATOR_REQUIRED);
        }
        PromptEntity prompt = promptMapper.selectByIdForUpdate(promptId);
        if (prompt == null) {
            return fail(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        PromptVersionEntity target = findVersion(promptId, versionId);
        if (target == null || !canPublish(target.getVersionStatus())) {
            writeReleaseLog(promptId, PromptReleaseOperationType.PUBLISH, versionId,
                    prompt.getCurrentVersionId(), form.getReason(), operator,
                    PromptReleaseResult.FAILED, PromptErrorCode.PROMPT_INVALID_STATE.getMessage());
            return fail(PromptErrorCode.PROMPT_INVALID_STATE);
        }
        PromptVersionEntity previous = currentVersion(prompt);
        if (previous != null && !previous.getId().equals(target.getId())) {
            previous.setVersionStatus(PromptVersionStatus.DEPRECATED.name());
            promptVersionMapper.updateById(previous);
        }
        target.setVersionStatus(PromptVersionStatus.PUBLISHED.name());
        target.setPublishedBy(operator);
        target.setPublishedTime(LocalDateTime.now());
        promptVersionMapper.updateById(target);
        prompt.setCurrentVersionId(target.getId());
        prompt.setStatus(PromptStatus.ACTIVE.name());
        prompt.setUpdatedBy(operator);
        promptMapper.updateById(prompt);
        writeReleaseLog(promptId, PromptReleaseOperationType.PUBLISH, target.getId(),
                previous == null ? null : previous.getId(), form.getReason(), operator,
                PromptReleaseResult.SUCCESS, null);
        log.info("Prompt version published: promptId={}, versionId={}, operator={}", promptId, versionId, operator);
        return Response.success(toVO(target, target.getId()));
    }

    @Transactional
    public Response<Void> offline(Long promptId, PromptOperationForm form) {
        if (!hasReason(form)) {
            return fail(PromptErrorCode.PROMPT_OPERATION_REASON_REQUIRED);
        }
        String operator = operator();
        if (operator == null) {
            return fail(PromptErrorCode.PROMPT_OPERATOR_REQUIRED);
        }
        PromptEntity prompt = promptMapper.selectByIdForUpdate(promptId);
        if (prompt == null) {
            return fail(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        PromptVersionEntity previous = currentVersion(prompt);
        if (previous == null || !PromptVersionStatus.PUBLISHED.name().equals(previous.getVersionStatus())) {
            writeReleaseLog(promptId, PromptReleaseOperationType.OFFLINE, null,
                    previous == null ? null : previous.getId(), form.getReason(), operator,
                    PromptReleaseResult.FAILED, PromptErrorCode.PROMPT_INVALID_STATE.getMessage());
            return fail(PromptErrorCode.PROMPT_INVALID_STATE);
        }
        previous.setVersionStatus(PromptVersionStatus.DEPRECATED.name());
        promptVersionMapper.updateById(previous);
        prompt.setCurrentVersionId(null);
        prompt.setStatus(PromptStatus.INACTIVE.name());
        prompt.setUpdatedBy(operator);
        promptMapper.updateById(prompt);
        writeReleaseLog(promptId, PromptReleaseOperationType.OFFLINE, null, previous.getId(),
                form.getReason(), operator, PromptReleaseResult.SUCCESS, null);
        log.info("Prompt version offline: promptId={}, versionId={}, operator={}", promptId, previous.getId(), operator);
        return Response.success();
    }

    @Transactional
    public Response<PromptVersionVO> rollback(Long promptId, PromptRollbackForm form) {
        if (form == null || form.getVersionId() == null || form.getReason() == null || form.getReason().isBlank()) {
            return fail(PromptErrorCode.PROMPT_OPERATION_REASON_REQUIRED);
        }
        String operator = operator();
        if (operator == null) {
            return fail(PromptErrorCode.PROMPT_OPERATOR_REQUIRED);
        }
        PromptEntity prompt = promptMapper.selectByIdForUpdate(promptId);
        if (prompt == null) {
            return fail(PromptErrorCode.PROMPT_NOT_FOUND);
        }
        PromptVersionEntity target = findVersion(promptId, form.getVersionId());
        if (target == null || !canRollback(target.getVersionStatus())) {
            writeReleaseLog(promptId, PromptReleaseOperationType.ROLLBACK, form.getVersionId(),
                    prompt.getCurrentVersionId(), form.getReason(), operator,
                    PromptReleaseResult.FAILED, PromptErrorCode.PROMPT_ROLLBACK_TARGET_INVALID.getMessage());
            return fail(PromptErrorCode.PROMPT_ROLLBACK_TARGET_INVALID);
        }
        PromptVersionEntity previous = currentVersion(prompt);
        if (previous != null && previous.getId().equals(target.getId())) {
            writeReleaseLog(promptId, PromptReleaseOperationType.ROLLBACK, form.getVersionId(),
                    previous.getId(), form.getReason(), operator,
                    PromptReleaseResult.FAILED, PromptErrorCode.PROMPT_ROLLBACK_TARGET_INVALID.getMessage());
            return fail(PromptErrorCode.PROMPT_ROLLBACK_TARGET_INVALID);
        }
        if (previous != null) {
            previous.setVersionStatus(PromptVersionStatus.ROLLED_BACK.name());
            promptVersionMapper.updateById(previous);
        }
        target.setVersionStatus(PromptVersionStatus.PUBLISHED.name());
        target.setPublishedBy(operator);
        target.setPublishedTime(LocalDateTime.now());
        promptVersionMapper.updateById(target);
        prompt.setCurrentVersionId(target.getId());
        prompt.setStatus(PromptStatus.ACTIVE.name());
        prompt.setUpdatedBy(operator);
        promptMapper.updateById(prompt);
        writeReleaseLog(promptId, PromptReleaseOperationType.ROLLBACK, target.getId(),
                previous == null ? null : previous.getId(), form.getReason(), operator,
                PromptReleaseResult.SUCCESS, null);
        log.info("Prompt version rolled back: promptId={}, versionId={}, operator={}", promptId, target.getId(), operator);
        return Response.success(toVO(target, target.getId()));
    }

    public List<PromptReleaseLogVO> listReleaseLogs(Long promptId) {
        return promptReleaseLogMapper.selectList(new LambdaQueryWrapper<PromptReleaseLogEntity>()
                        .eq(PromptReleaseLogEntity::getPromptId, promptId)
                        .orderByDesc(PromptReleaseLogEntity::getCreateTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private PromptVersionEntity currentVersion(PromptEntity prompt) {
        return prompt.getCurrentVersionId() == null
                ? null
                : promptVersionMapper.selectById(prompt.getCurrentVersionId());
    }

    private PromptVersionEntity findVersion(Long promptId, Long versionId) {
        return promptVersionMapper.selectOne(new LambdaQueryWrapper<PromptVersionEntity>()
                .eq(PromptVersionEntity::getId, versionId)
                .eq(PromptVersionEntity::getPromptId, promptId));
    }

    private boolean canPublish(String status) {
        return PromptVersionStatus.DRAFT.name().equals(status)
                || PromptVersionStatus.DEPRECATED.name().equals(status)
                || PromptVersionStatus.ROLLED_BACK.name().equals(status);
    }

    private boolean canRollback(String status) {
        return PromptVersionStatus.DEPRECATED.name().equals(status)
                || PromptVersionStatus.ROLLED_BACK.name().equals(status);
    }

    private void writeReleaseLog(Long promptId, PromptReleaseOperationType operationType,
                                 Long targetVersionId, Long previousVersionId, String reason,
                                 String operator, PromptReleaseResult result, String failureReason) {
        PromptReleaseLogEntity logEntity = new PromptReleaseLogEntity();
        logEntity.setPromptId(promptId);
        logEntity.setOperationType(operationType.name());
        logEntity.setTargetVersionId(targetVersionId);
        logEntity.setPreviousVersionId(previousVersionId);
        logEntity.setOperationReason(reason.trim());
        logEntity.setOperatorId(operator);
        logEntity.setOperationResult(result.name());
        logEntity.setFailureReason(failureReason);
        promptReleaseLogMapper.insert(logEntity);
    }

    private String operator() {
        String operator = operatorIdentityProvider.currentOperatorId();
        return operator == null || operator.isBlank() ? null : operator.trim();
    }

    private String contentHash(PromptVersionSaveForm form) {
        String content = String.join("\n", nullToEmpty(form.getSystemPrompt()),
                nullToEmpty(form.getUserTemplate()), nullToEmpty(form.getOutputSchema()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private PromptVO toVO(PromptEntity entity) {
        PromptVO vo = new PromptVO();
        vo.setId(entity.getId());
        vo.setPromptKey(entity.getPromptKey());
        vo.setPromptName(entity.getPromptName());
        vo.setAgentCapability(entity.getAgentCapability());
        vo.setDescription(entity.getDescription());
        vo.setCurrentVersionId(entity.getCurrentVersionId());
        vo.setStatus(entity.getStatus());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setUpdatedBy(entity.getUpdatedBy());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private PromptVersionVO toVO(PromptVersionEntity entity, Long currentVersionId) {
        PromptVersionVO vo = new PromptVersionVO();
        vo.setId(entity.getId());
        vo.setPromptId(entity.getPromptId());
        vo.setVersionNo(entity.getVersionNo());
        vo.setSystemPrompt(entity.getSystemPrompt());
        vo.setUserTemplate(entity.getUserTemplate());
        vo.setOutputSchema(entity.getOutputSchema());
        vo.setContentHash(entity.getContentHash());
        vo.setVersionStatus(entity.getVersionStatus());
        vo.setChangeReason(entity.getChangeReason());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedTime(entity.getCreatedTime());
        vo.setPublishedBy(entity.getPublishedBy());
        vo.setPublishedTime(entity.getPublishedTime());
        vo.setCurrent(entity.getId() != null && entity.getId().equals(currentVersionId));
        return vo;
    }

    private PromptReleaseLogVO toVO(PromptReleaseLogEntity entity) {
        PromptReleaseLogVO vo = new PromptReleaseLogVO();
        vo.setId(entity.getId());
        vo.setPromptId(entity.getPromptId());
        vo.setOperationType(entity.getOperationType());
        vo.setTargetVersionId(entity.getTargetVersionId());
        vo.setPreviousVersionId(entity.getPreviousVersionId());
        vo.setOperationReason(entity.getOperationReason());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperationResult(entity.getOperationResult());
        vo.setFailureReason(entity.getFailureReason());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    private <T> Response<T> fail(PromptErrorCode errorCode) {
        return Response.fail(errorCode.getCode(), errorCode.getMessage());
    }

    private <T> PageResponse<T> failPage(PromptErrorCode errorCode) {
        PageResponse<T> response = new PageResponse<>();
        response.setSuccess(false);
        response.setErrorCode(errorCode.getCode());
        response.setMessage(errorCode.getMessage());
        return response;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasReason(PromptOperationForm form) {
        return form != null && form.getReason() != null && !form.getReason().isBlank();
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

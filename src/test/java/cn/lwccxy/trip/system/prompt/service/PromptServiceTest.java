package cn.lwccxy.trip.system.prompt.service;

import cn.lwccxy.trip.system.prompt.domain.entity.PromptEntity;
import cn.lwccxy.trip.system.prompt.domain.entity.PromptReleaseLogEntity;
import cn.lwccxy.trip.system.prompt.domain.entity.PromptVersionEntity;
import cn.lwccxy.trip.system.prompt.domain.form.PromptOperationForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptRollbackForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptSaveForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptVersionSaveForm;
import cn.lwccxy.trip.system.prompt.enums.PromptVersionStatus;
import cn.lwccxy.trip.system.prompt.mapper.PromptMapper;
import cn.lwccxy.trip.system.prompt.mapper.PromptReleaseLogMapper;
import cn.lwccxy.trip.system.prompt.mapper.PromptVersionMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private PromptMapper promptMapper;

    @Mock
    private PromptVersionMapper promptVersionMapper;

    @Mock
    private PromptReleaseLogMapper promptReleaseLogMapper;

    @Mock
    private OperatorIdentityProvider operatorIdentityProvider;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new PromptService();
        ReflectionTestUtils.setField(promptService, "promptMapper", promptMapper);
        ReflectionTestUtils.setField(promptService, "promptVersionMapper", promptVersionMapper);
        ReflectionTestUtils.setField(promptService, "promptReleaseLogMapper", promptReleaseLogMapper);
        ReflectionTestUtils.setField(promptService, "operatorIdentityProvider", operatorIdentityProvider);
        when(operatorIdentityProvider.currentOperatorId()).thenReturn("tester");
    }

    @Test
    void addVersionRejectsNonIncreasingVersion() {
        PromptEntity prompt = prompt(7L, null);
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectMaxVersionNo(7L)).thenReturn(3);

        PromptVersionSaveForm form = versionForm(3);

        var response = promptService.addVersion(7L, form);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PROMPT_VERSION_NOT_INCREASING");
        verify(promptVersionMapper, never()).insert(any(PromptVersionEntity.class));
    }

    @Test
    void addVersionRejectsMissingAuthenticatedOperator() {
        when(operatorIdentityProvider.currentOperatorId()).thenReturn(null);

        var response = promptService.addVersion(7L, versionForm(1));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PROMPT_OPERATOR_REQUIRED");
        verify(promptMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void addVersionCreatesImmutableRecordWithContentHash() {
        PromptEntity prompt = prompt(7L, null);
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectMaxVersionNo(7L)).thenReturn(3);

        var response = promptService.addVersion(7L, versionForm(4));

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<PromptVersionEntity> captor = ArgumentCaptor.forClass(PromptVersionEntity.class);
        verify(promptVersionMapper).insert(captor.capture());
        PromptVersionEntity saved = captor.getValue();
        assertThat(saved.getVersionNo()).isEqualTo(4);
        assertThat(saved.getVersionStatus()).isEqualTo(PromptVersionStatus.DRAFT.name());
        assertThat(saved.getCreatedBy()).isEqualTo("tester");
        assertThat(saved.getContentHash()).hasSize(64);
    }

    @Test
    void publishReplacesCurrentVersionAndWritesAuditLog() {
        PromptEntity prompt = prompt(7L, 1L);
        PromptVersionEntity previous = version(1L, 7L, 1, PromptVersionStatus.PUBLISHED.name());
        PromptVersionEntity target = version(2L, 7L, 2, PromptVersionStatus.DRAFT.name());
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectById(1L)).thenReturn(previous);
        when(promptVersionMapper.selectOne(any(Wrapper.class))).thenReturn(target);

        PromptOperationForm form = new PromptOperationForm();
        form.setReason("发布新版本");
        var response = promptService.publish(7L, 2L, form);

        assertThat(response.isSuccess()).isTrue();
        assertThat(prompt.getCurrentVersionId()).isEqualTo(2L);
        assertThat(previous.getVersionStatus()).isEqualTo(PromptVersionStatus.DEPRECATED.name());
        assertThat(target.getVersionStatus()).isEqualTo(PromptVersionStatus.PUBLISHED.name());
        verify(promptMapper).updateById(prompt);
        verify(promptReleaseLogMapper).insert(any(PromptReleaseLogEntity.class));
    }

    @Test
    void rollbackRejectsDraftTarget() {
        PromptEntity prompt = prompt(7L, 1L);
        PromptVersionEntity target = version(2L, 7L, 2, PromptVersionStatus.DRAFT.name());
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectOne(any(Wrapper.class))).thenReturn(target);

        PromptRollbackForm form = new PromptRollbackForm();
        form.setVersionId(2L);
        form.setReason("回滚验证");
        var response = promptService.rollback(7L, form);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PROMPT_ROLLBACK_TARGET_INVALID");
        verify(promptMapper, never()).updateById(any(PromptEntity.class));
    }

    @Test
    void offlineClearsCurrentVersionAndMarksPromptInactive() {
        PromptEntity prompt = prompt(7L, 1L);
        PromptVersionEntity current = version(1L, 7L, 1, PromptVersionStatus.PUBLISHED.name());
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectById(1L)).thenReturn(current);

        PromptOperationForm form = new PromptOperationForm();
        form.setReason("暂时下线");
        var response = promptService.offline(7L, form);

        assertThat(response.isSuccess()).isTrue();
        assertThat(prompt.getCurrentVersionId()).isNull();
        assertThat(prompt.getStatus()).isEqualTo("INACTIVE");
        assertThat(current.getVersionStatus()).isEqualTo(PromptVersionStatus.DEPRECATED.name());
        verify(promptReleaseLogMapper).insert(any(PromptReleaseLogEntity.class));
    }

    @Test
    void rollbackRestoresHistoricalVersion() {
        PromptEntity prompt = prompt(7L, 2L);
        PromptVersionEntity current = version(2L, 7L, 2, PromptVersionStatus.PUBLISHED.name());
        PromptVersionEntity target = version(1L, 7L, 1, PromptVersionStatus.DEPRECATED.name());
        when(promptMapper.selectByIdForUpdate(7L)).thenReturn(prompt);
        when(promptVersionMapper.selectById(2L)).thenReturn(current);
        when(promptVersionMapper.selectOne(any(Wrapper.class))).thenReturn(target);

        PromptRollbackForm form = new PromptRollbackForm();
        form.setVersionId(1L);
        form.setReason("恢复上一版");
        var response = promptService.rollback(7L, form);

        assertThat(response.isSuccess()).isTrue();
        assertThat(prompt.getCurrentVersionId()).isEqualTo(1L);
        assertThat(current.getVersionStatus()).isEqualTo(PromptVersionStatus.ROLLED_BACK.name());
        assertThat(target.getVersionStatus()).isEqualTo(PromptVersionStatus.PUBLISHED.name());
        verify(promptReleaseLogMapper).insert(any(PromptReleaseLogEntity.class));
    }

    private PromptEntity prompt(Long id, Long currentVersionId) {
        PromptEntity entity = new PromptEntity();
        entity.setId(id);
        entity.setCurrentVersionId(currentVersionId);
        return entity;
    }

    private PromptVersionEntity version(Long id, Long promptId, int versionNo, String status) {
        PromptVersionEntity entity = new PromptVersionEntity();
        entity.setId(id);
        entity.setPromptId(promptId);
        entity.setVersionNo(versionNo);
        entity.setVersionStatus(status);
        return entity;
    }

    private PromptVersionSaveForm versionForm(int versionNo) {
        PromptVersionSaveForm form = new PromptVersionSaveForm();
        form.setVersionNo(versionNo);
        form.setSystemPrompt("You are a travel assistant.");
        form.setUserTemplate("{message}");
        form.setOutputSchema("{\"type\":\"object\"}");
        form.setChangeReason("改进出差申请信息收集");
        return form;
    }
}

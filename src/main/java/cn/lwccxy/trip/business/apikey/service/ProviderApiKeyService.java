package cn.lwccxy.trip.business.apikey.service;

import cn.lwccxy.trip.business.apikey.domain.entity.ProviderApiKeyEntity;
import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeyQueryForm;
import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeySaveForm;
import cn.lwccxy.trip.business.apikey.domain.vo.ProviderApiKeyVO;
import cn.lwccxy.trip.business.apikey.mapper.ProviderApiKeyMapper;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** 提供当前用户第三方服务商 API Key 的安全 CRUD 能力。 */
@Service
@RequiredArgsConstructor
public class ProviderApiKeyService {

    private static final String DEFAULT_STATUS = "ACTIVE";

    private final ProviderApiKeyMapper mapper;
    private final ApiKeyEncryptionService encryptionService;

    /** 分页查询当前用户的服务商凭证，响应中不包含 API Key 密文。 */
    public PageResponse<ProviderApiKeyVO> query(Long userId, ProviderApiKeyQueryForm form) {
        String providerCode = normalizeProviderCode(form.getProviderCode());
        LambdaQueryWrapper<ProviderApiKeyEntity> query = new LambdaQueryWrapper<ProviderApiKeyEntity>()
                .eq(ProviderApiKeyEntity::getUserId, userId);
        if (providerCode != null) {
            query.eq(ProviderApiKeyEntity::getProviderCode, providerCode);
        }
        if (form.getStatus() != null && !"ALL".equals(form.getStatus())) {
            query.eq(ProviderApiKeyEntity::getStatus, form.getStatus());
        }
        query.orderByDesc(ProviderApiKeyEntity::getUpdateTime)
                .orderByDesc(ProviderApiKeyEntity::getId);

        Page<ProviderApiKeyEntity> page = mapper.selectPage(
                new Page<>(form.getPageNo(), form.getPageSize()), query);
        List<ProviderApiKeyVO> data = page.getRecords().stream().map(this::toVO).toList();
        return PageResponse.success(data, page.getCurrent(), page.getTotal(), page.getSize());
    }

    /** 查询当前用户拥有的单个服务商凭证。 */
    public Response<ProviderApiKeyVO> get(Long userId, Long id) {
        ProviderApiKeyEntity entity = mapper.selectOwned(userId, id);
        return entity == null
                ? Response.fail("PROVIDER_API_KEY_NOT_FOUND", "Provider API key does not exist")
                : Response.success(toVO(entity));
    }

    /** 加密并创建当前用户的服务商凭证。 */
    @Transactional
    public Response<ProviderApiKeyVO> create(Long userId, ProviderApiKeySaveForm form) {
        ProviderApiKeyEntity entity = toEntity(userId, form);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            return Response.fail("PROVIDER_API_KEY_ALREADY_EXISTS",
                    "The provider API key already exists for this user");
        }
        return Response.success(toVO(entity));
    }

    /** 加密并更新当前用户拥有的服务商凭证。 */
    @Transactional
    public Response<ProviderApiKeyVO> update(Long userId, Long id, ProviderApiKeySaveForm form) {
        ProviderApiKeyEntity old = mapper.selectOwned(userId, id);
        if (old == null) {
            return Response.fail("PROVIDER_API_KEY_NOT_FOUND", "Provider API key does not exist");
        }
        ProviderApiKeyEntity entity = toEntity(userId, form);
        entity.setId(id);
        entity.setCreateTime(old.getCreateTime());
        try {
            mapper.updateOwned(userId, id, entity.getProviderCode(), entity.getApiKeyCiphertext(),
                    entity.getStatus(), entity.getRemark(), userId);
        } catch (DuplicateKeyException exception) {
            return Response.fail("PROVIDER_API_KEY_ALREADY_EXISTS",
                    "The provider API key already exists for this user");
        }
        return Response.success(toVO(mapper.selectOwned(userId, id)));
    }

    /** 删除当前用户拥有的服务商凭证。 */
    @Transactional
    public Response<Void> delete(Long userId, Long id) {
        ProviderApiKeyEntity entity = mapper.selectOwned(userId, id);
        if (entity == null) {
            return Response.fail("PROVIDER_API_KEY_NOT_FOUND", "Provider API key does not exist");
        }
        mapper.deleteOwned(userId, id);
        return Response.success();
    }

    /** 按服务商编码读取当前用户的可用 API Key 明文，仅供后端调用第三方服务。 */
    public String getActiveApiKey(Long userId, String providerCode) {
        String normalizedProviderCode = normalizeProviderCode(providerCode);
        if (normalizedProviderCode == null) {
            return null;
        }
        ProviderApiKeyEntity entity = mapper.selectOne(new LambdaQueryWrapper<ProviderApiKeyEntity>()
                .eq(ProviderApiKeyEntity::getUserId, userId)
                .eq(ProviderApiKeyEntity::getProviderCode, normalizedProviderCode)
                .eq(ProviderApiKeyEntity::getStatus, DEFAULT_STATUS));
        return entity == null ? null : encryptionService.decrypt(entity.getApiKeyCiphertext());
    }

    /** 将请求参数转换为数据库实体，并在持久化前完成 API Key 加密。 */
    private ProviderApiKeyEntity toEntity(Long userId, ProviderApiKeySaveForm form) {
        ProviderApiKeyEntity entity = new ProviderApiKeyEntity();
        entity.setUserId(userId);
        entity.setProviderCode(normalizeProviderCode(form.getProviderCode()));
        entity.setApiKeyCiphertext(encryptionService.encrypt(form.getApiKey().strip()));
        entity.setStatus(form.getStatus() == null ? DEFAULT_STATUS : form.getStatus());
        entity.setRemark(normalizeRemark(form.getRemark()));
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        return entity;
    }

    /** 将数据库实体转换为不包含 API Key 密文的返回对象。 */
    private ProviderApiKeyVO toVO(ProviderApiKeyEntity entity) {
        ProviderApiKeyVO vo = new ProviderApiKeyVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setProviderCode(entity.getProviderCode());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    /** 统一服务商编码格式，避免大小写导致同一平台出现重复记录。 */
    private String normalizeProviderCode(String providerCode) {
        return providerCode == null || providerCode.isBlank()
                ? null : providerCode.strip().toUpperCase(Locale.ROOT);
    }

    /** 统一备注字段的空值表示。 */
    private String normalizeRemark(String remark) {
        return remark == null || remark.isBlank() ? null : remark.strip();
    }
}

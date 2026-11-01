package cn.lwccxy.trip.business.apikey.controller;

import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeyQueryForm;
import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeySaveForm;
import cn.lwccxy.trip.business.apikey.domain.vo.ProviderApiKeyVO;
import cn.lwccxy.trip.business.apikey.service.ProviderApiKeyService;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前登录用户第三方服务商 API Key 的 CRUD 接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/provider-api-keys")
public class ProviderApiKeyController {

    private final CurrentUser currentUser;
    private final ProviderApiKeyService service;

    /** 分页查询当前登录用户的服务商 API Key。 */
    @GetMapping
    public PageResponse<ProviderApiKeyVO> query(@Valid @ModelAttribute ProviderApiKeyQueryForm form) {
        return service.query(ownerId(), form);
    }

    /** 查询当前登录用户拥有的单个服务商 API Key。 */
    @GetMapping("/{id}")
    public Response<ProviderApiKeyVO> get(@Positive @PathVariable Long id) {
        return service.get(ownerId(), id);
    }

    /** 创建当前登录用户的服务商 API Key。 */
    @PostMapping
    public Response<ProviderApiKeyVO> create(@Valid @RequestBody ProviderApiKeySaveForm form) {
        return service.create(ownerId(), form);
    }

    /** 更新当前登录用户的服务商 API Key。 */
    @PutMapping("/{id}")
    public Response<ProviderApiKeyVO> update(@Positive @PathVariable Long id,
                                             @Valid @RequestBody ProviderApiKeySaveForm form) {
        return service.update(ownerId(), id, form);
    }

    /** 删除当前登录用户的服务商 API Key。 */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@Positive @PathVariable Long id) {
        return service.delete(ownerId(), id);
    }

    /** 获取当前认证用户 ID，确保凭证接口始终按登录用户隔离数据。 */
    private Long ownerId() {
        return currentUser.get().getId();
    }
}

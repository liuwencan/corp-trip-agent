package cn.lwccxy.trip.business.preference.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.lwccxy.trip.business.preference.domain.form.PreferenceSaveForm;
import cn.lwccxy.trip.business.preference.domain.vo.PreferenceCategoryVO;
import cn.lwccxy.trip.business.preference.domain.vo.UserPreferenceVO;
import cn.lwccxy.trip.business.preference.service.PreferenceService;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供当前登录用户的长期记忆偏好展示和保存接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/preferences", "/api/preferences"})
public class PreferenceController {

    private final PreferenceService preferenceService;

    /** 查询前端可展示和编辑的偏好类别及选项。 */
    @SaCheckLogin
    @GetMapping("/options")
    public Response<List<PreferenceCategoryVO>> getPreferenceOptions() {
        return Response.success(preferenceService.getPreferenceOptions());
    }

    /** 从当前用户长期记忆实时召回偏好并返回结构化展示数据。 */
    @SaCheckLogin
    @GetMapping
    public Response<UserPreferenceVO> getUserPreferences() {
        return Response.success(preferenceService.getUserPreferences());
    }

    /** 校验并将当前用户提交的偏好保存至长期记忆。 */
    @SaCheckLogin
    @PostMapping
    public Response<UserPreferenceVO> saveUserPreferences(@Valid @RequestBody PreferenceSaveForm form) {
        return preferenceService.saveUserPreferences(form);
    }
}

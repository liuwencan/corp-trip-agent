package cn.lwccxy.trip.business.userprofile.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.lwccxy.trip.business.userprofile.domain.form.PersonalProfileSaveForm;
import cn.lwccxy.trip.business.userprofile.domain.vo.PersonalProfileVO;
import cn.lwccxy.trip.business.userprofile.domain.vo.ProfileOptionsVO;
import cn.lwccxy.trip.business.userprofile.service.PersonalProfileService;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 当前登录用户的个人中心接口，不提供通过用户标识访问他人资料的入口。 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping({"/user/profile", "/api/user/profile"})
public class PersonalProfileController {
    private final PersonalProfileService service;

    /** 查询账户公开资料及出行档案，尚未填写时档案为空。 */
    @GetMapping
    public Response<PersonalProfileVO> get() {
        return Response.success(service.get());
    }

    /** 首次创建当前用户的个人档案。 */
    @PostMapping
    public Response<PersonalProfileVO> create(@Valid @RequestBody PersonalProfileSaveForm form) {
        return Response.success(service.create(form));
    }

    /** 局部更新当前用户的资料，并支持显式清空指定字段。 */
    @PutMapping
    public Response<PersonalProfileVO> update(@Valid @RequestBody PersonalProfileSaveForm form) {
        return Response.success(service.update(form));
    }

    /** 清除当前用户的个人资料，不注销账户。 */
    @DeleteMapping
    public Response<Void> delete() {
        service.delete();
        return Response.success();
    }

    /** 查询前端可选的职级、性别、证件类型和可编辑字段。 */
    @GetMapping("/options")
    public Response<ProfileOptionsVO> options() {
        return Response.success(service.options());
    }
}

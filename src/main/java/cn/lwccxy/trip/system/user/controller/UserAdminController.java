package cn.lwccxy.trip.system.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.user.domain.form.ResetPasswordForm;
import cn.lwccxy.trip.system.user.domain.form.UserQueryForm;
import cn.lwccxy.trip.system.user.domain.vo.UserVO;
import cn.lwccxy.trip.system.user.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SaCheckRole("ADMIN")
@RequestMapping("/system/user")
public class UserAdminController {
    @Resource private UserService userService;

    @PostMapping("/query")
    public PageResponse<UserVO> query(@RequestBody @Valid UserQueryForm form) {
        return userService.query(form);
    }

    @PostMapping("/{id}/reset-password")
    public Response<Void> reset(@PathVariable @jakarta.validation.constraints.Positive Long id, @RequestBody @Valid ResetPasswordForm form) {
        return userService.resetPassword(id, form);
    }
}

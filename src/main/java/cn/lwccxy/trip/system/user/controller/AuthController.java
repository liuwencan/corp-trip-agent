package cn.lwccxy.trip.system.user.controller;

import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.user.domain.form.ChangePasswordForm;
import cn.lwccxy.trip.system.user.domain.form.LoginForm;
import cn.lwccxy.trip.system.user.domain.form.RegisterForm;
import cn.lwccxy.trip.system.user.domain.vo.UserVO;
import cn.lwccxy.trip.system.user.domain.vo.LoginVO;
import cn.lwccxy.trip.system.user.service.UserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Resource private UserService userService;

    @PostMapping("/register")
    public Response<UserVO> register(@RequestBody @Valid RegisterForm form) { return userService.register(form); }

    @PostMapping("/login")
    public Response<LoginVO> login(@RequestBody @Valid LoginForm form) { return userService.login(form); }

    @PostMapping("/logout")
    public Response<Void> logout() { userService.logout(); return Response.success(); }

    @GetMapping("/me")
    public Response<UserVO> me() { return userService.me(); }

    @PutMapping("/password")
    public Response<Void> password(@RequestBody @Valid ChangePasswordForm form) { return userService.changePassword(form); }
}

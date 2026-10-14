package cn.lwccxy.trip.common.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.system.user.domain.entity.UserEntity;
import cn.lwccxy.trip.system.user.enums.UserErrorCode;
import cn.lwccxy.trip.system.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class CurrentUser {
    public static final String CREDENTIAL_VERSION = "credentialVersion";
    private static final String REQUEST_USER = CurrentUser.class.getName();
    private final UserMapper userMapper;

    public UserEntity get() {
        StpUtil.checkLogin();
        var attributes = RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        if (request != null && request.getAttribute(REQUEST_USER) instanceof UserEntity cached) {
            return cached;
        }
        UserEntity user = userMapper.selectById(StpUtil.getLoginIdAsLong());
        Object version = StpUtil.getTokenSession().get(CREDENTIAL_VERSION);
        if (user == null || version == null || !user.getCredentialVersion().equals(version)) {
            throw new BizException(UserErrorCode.AUTH_NOT_LOGIN);
        }
        if (request != null) request.setAttribute(REQUEST_USER, user);
        return user;
    }
}

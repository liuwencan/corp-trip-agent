package cn.lwccxy.trip.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.system.user.domain.entity.PasswordResetLogEntity;
import cn.lwccxy.trip.system.user.domain.entity.UserEntity;
import cn.lwccxy.trip.system.user.domain.form.ChangePasswordForm;
import cn.lwccxy.trip.system.user.domain.form.LoginForm;
import cn.lwccxy.trip.system.user.domain.form.RegisterForm;
import cn.lwccxy.trip.system.user.domain.form.ResetPasswordForm;
import cn.lwccxy.trip.system.user.domain.form.UserQueryForm;
import cn.lwccxy.trip.system.user.domain.vo.LoginVO;
import cn.lwccxy.trip.system.user.domain.vo.UserVO;
import cn.lwccxy.trip.system.user.enums.UserErrorCode;
import cn.lwccxy.trip.system.user.enums.UserRole;
import cn.lwccxy.trip.system.user.mapper.PasswordResetLogMapper;
import cn.lwccxy.trip.system.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
public class UserService extends ServiceImpl<UserMapper, UserEntity> {
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    private final String dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    @Resource private CurrentUser currentUser;

    @Resource
    private UserMapper userMapper;
    @Resource
    private PasswordResetLogMapper passwordResetLogMapper;

    public Response<UserVO> register(RegisterForm form) {
        String username = normalizeUsername(form.getUsername());
        if (!validUsername(username) || !validPassword(form.getPassword())) {
            throw new BizException(UserErrorCode.INVALID_PARAMETER);
        }
        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        entity.setRole(UserRole.USER.name());
        entity.setMustChangePassword(false);
        entity.setCredentialVersion(0L);
        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw new BizException(UserErrorCode.USER_ALREADY_EXISTS);
        }
        return Response.success(toVO(entity));
    }

    public Response<LoginVO> login(LoginForm form) {
        String username = normalizeUsername(form.getUsername());
        UserEntity entity = userMapper.selectByUsername(username);
        if (!validUsername(username) || !validPassword(form.getPassword())) throw new BizException(UserErrorCode.INVALID_PARAMETER);
        boolean matched = passwordEncoder.matches(form.getPassword(), entity == null ? dummyHash : entity.getPasswordHash());
        if (entity == null || !matched) {
            throw new BizException(UserErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        StpUtil.login(entity.getId());
        String token = StpUtil.getTokenValue();
        StpUtil.getTokenSession().set(CurrentUser.CREDENTIAL_VERSION, entity.getCredentialVersion());
        LoginVO result = new LoginVO();
        result.setTokenName("satoken");
        result.setTokenValue(token);
        result.setExpiresIn(StpUtil.getTokenTimeout());
        result.setUser(toVO(entity));
        return Response.success(result);
    }

    public void logout() {
        StpUtil.logout();
    }

    public UserEntity currentUser() {
        return currentUser.get();
    }

    public Response<UserVO> me() {
        return Response.success(toVO(currentUser()));
    }

    @Transactional
    public Response<Void> changePassword(ChangePasswordForm form) {
        UserEntity current = currentUser();
        UserEntity entity = userMapper.selectByIdForUpdate(current.getId());
        if (entity == null || !passwordEncoder.matches(form.getOldPassword(), entity.getPasswordHash())) {
            throw new BizException(UserErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!validPassword(form.getNewPassword())) {
            throw new BizException(UserErrorCode.INVALID_PARAMETER);
        }
        if (passwordEncoder.matches(form.getNewPassword(), entity.getPasswordHash())) {
            throw new BizException(UserErrorCode.PASSWORD_UNCHANGED);
        }
        entity.setPasswordHash(passwordEncoder.encode(form.getNewPassword()));
        entity.setMustChangePassword(false);
        entity.setCredentialVersion(entity.getCredentialVersion() + 1);
        userMapper.updateById(entity);
        invalidateUser(entity.getId());
        return Response.success();
    }

    @Transactional
    public Response<Void> resetPassword(Long targetId, ResetPasswordForm form) {
        UserEntity operator = currentUser();
        if (!isAdmin(operator) || isPasswordChangeRequired(operator)) throw new BizException(UserErrorCode.AUTH_FORBIDDEN);
        UserEntity entity = userMapper.selectByIdForUpdate(targetId);
        if (entity == null) {
            throw new BizException(UserErrorCode.USER_NOT_FOUND);
        }
        String reason = form.getReason() == null ? "" : form.getReason().trim();
        if (!validPassword(form.getTemporaryPassword()) || reason.isBlank() || reason.length() > 1000) {
            throw new BizException(UserErrorCode.INVALID_PARAMETER);
        }
        if (passwordEncoder.matches(form.getTemporaryPassword(), entity.getPasswordHash())) {
            throw new BizException(UserErrorCode.PASSWORD_UNCHANGED);
        }
        entity.setPasswordHash(passwordEncoder.encode(form.getTemporaryPassword()));
        entity.setMustChangePassword(true);
        entity.setCredentialVersion(entity.getCredentialVersion() + 1);
        userMapper.updateById(entity);
        PasswordResetLogEntity log = new PasswordResetLogEntity();
        log.setTargetUserId(targetId);
        log.setOperatorId(operator.getId());
        log.setReason(reason);
        passwordResetLogMapper.insert(log);
        invalidateUser(targetId);
        return Response.success();
    }

    public PageResponse<UserVO> query(UserQueryForm form) {
        if (form.getRole() != null && !form.getRole().isBlank()) {
            try { UserRole.valueOf(form.getRole()); }
            catch (IllegalArgumentException ex) { throw new BizException(UserErrorCode.INVALID_PARAMETER); }
        }
        if (form.getPageNo() < 1 || form.getPageSize() < 1 || form.getPageSize() > 200 ||
                form.getPageNo() > Long.MAX_VALUE / form.getPageSize()) throw new BizException(UserErrorCode.INVALID_PARAMETER);
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<>();
        if (form.getKeyword() != null && !form.getKeyword().isBlank()) {
            query.like(UserEntity::getUsername, normalizeUsername(form.getKeyword()));
        }
        if (form.getRole() != null && !form.getRole().isBlank()) {
            query.eq(UserEntity::getRole, form.getRole().trim().toUpperCase(Locale.ROOT));
        }
        long total = userMapper.selectCount(query);
        query.orderByDesc(UserEntity::getCreateTime).orderByDesc(UserEntity::getId);
        long offset = (form.getPageNo() - 1) * form.getPageSize();
        query.last("LIMIT " + offset + ", " + form.getPageSize());
        var records = userMapper.selectList(query);
        return PageResponse.success(records.stream().map(this::toVO).toList(), form.getPageNo(), total, form.getPageSize());
    }

    public boolean isAdmin(UserEntity user) {
        return user != null && UserRole.ADMIN.name().equals(user.getRole());
    }

    public boolean isPasswordChangeRequired(UserEntity user) {
        return user != null && Boolean.TRUE.equals(user.getMustChangePassword());
    }

    public UserVO toVO(UserEntity entity) {
        UserVO vo = new UserVO();
        vo.setId(entity.getId()); vo.setUsername(entity.getUsername()); vo.setRole(entity.getRole());
        vo.setMustChangePassword(entity.getMustChangePassword()); vo.setCreateTime(entity.getCreateTime()); vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validUsername(String username) {
        return username != null && username.matches("[a-z0-9_]{3,32}");
    }

    public static boolean validPassword(String password) {
        return password != null && password.length() >= 8 && password.length() <= 64
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private void invalidateUser(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try { StpUtil.logout(userId); }
                catch (RuntimeException ex) {
                    // Database credential versions still reject every older token.
                    log.warn("Session cleanup failed for user {}", userId);
                }
            }
        });
    }
}

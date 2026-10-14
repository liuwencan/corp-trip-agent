package cn.lwccxy.trip.common.auth;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UserRoleProvider implements StpInterface {
    private final CurrentUser currentUser;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) { return List.of(); }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        var user = currentUser.get();
        return String.valueOf(user.getId()).equals(String.valueOf(loginId)) ? List.of(user.getRole()) : List.of();
    }
}

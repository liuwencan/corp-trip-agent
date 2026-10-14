package cn.lwccxy.trip.system.prompt.service;
import cn.lwccxy.trip.common.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class DefaultOperatorIdentityProvider implements OperatorIdentityProvider {
    private final CurrentUser currentUser;
    @Override
    public String currentOperatorId() { return currentUser.get().getId().toString(); }
}

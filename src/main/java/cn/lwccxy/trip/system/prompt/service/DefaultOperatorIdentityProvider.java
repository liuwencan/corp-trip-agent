package cn.lwccxy.trip.system.prompt.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;

/**
 * Reads the authenticated HTTP principal until a project-wide identity service is connected.
 */
@Component
public class DefaultOperatorIdentityProvider implements OperatorIdentityProvider {

    @Override
    public String currentOperatorId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        Principal principal = servletAttributes.getRequest().getUserPrincipal();
        return principal == null ? null : principal.getName();
    }
}

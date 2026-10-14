package cn.lwccxy.trip.common.config;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.system.user.enums.UserErrorCode;
import cn.lwccxy.trip.system.user.service.UserService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
@Configuration
@RequiredArgsConstructor
public class AuthWebConfig implements WebMvcConfigurer {
    private final UserService userService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                String method = request.getMethod();
                if (request.getDispatcherType() == DispatcherType.ERROR || CorsUtils.isPreFlightRequest(request)
                        || ("POST".equals(method) && ("/auth/register".equals(path) || "/auth/login".equals(path)))) {
                    return true;
                }
                StpUtil.checkLogin();
                var user = userService.currentUser();
                boolean recovery = ("GET".equals(method) && "/auth/me".equals(path))
                        || ("PUT".equals(method) && "/auth/password".equals(path))
                        || ("POST".equals(method) && "/auth/logout".equals(path));
                if (userService.isPasswordChangeRequired(user) && !recovery) {
                    throw new BizException(UserErrorCode.PASSWORD_CHANGE_REQUIRED);
                }
                if (managementPath(path, "/config") || managementPath(path, "/system/prompt")
                        || managementPath(path, "/system/user")) {
                    StpUtil.checkRole("ADMIN");
                }
                return true;
            }
        }).addPathPatterns("/**");
    }

    private static boolean managementPath(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}

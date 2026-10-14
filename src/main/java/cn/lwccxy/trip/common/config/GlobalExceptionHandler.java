package cn.lwccxy.trip.common.config;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.system.user.enums.UserErrorCode;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Response<Void>> business(BizException ex) {
        int status = switch (ex.getErrorCode()) {
            case "AUTH_NOT_LOGIN", "AUTH_INVALID_CREDENTIALS" -> 401;
            case "AUTH_FORBIDDEN", "PASSWORD_CHANGE_REQUIRED" -> 403;
            case "USER_ALREADY_EXISTS" -> 409;
            case "USER_NOT_FOUND" -> 404;
            default -> 400;
        };
        return ResponseEntity.status(status).body(Response.fail(ex));
    }
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Response<Void>> unauthenticated() { return ResponseEntity.status(401).body(Response.fail(UserErrorCode.AUTH_NOT_LOGIN)); }
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<Response<Void>> forbidden() { return ResponseEntity.status(403).body(Response.fail(UserErrorCode.AUTH_FORBIDDEN)); }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class,
        HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
        HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<Response<Void>> invalid(Exception ex) { return ResponseEntity.status(400).body(Response.fail(UserErrorCode.INVALID_PARAMETER)); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> unexpected(Exception ex) { return ResponseEntity.status(500).body(Response.fail("INTERNAL_ERROR", "Internal server error")); }
}

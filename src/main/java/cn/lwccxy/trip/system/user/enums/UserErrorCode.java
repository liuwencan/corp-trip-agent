package cn.lwccxy.trip.system.user.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;

@Getter
public enum UserErrorCode implements BaseExceptionInterface {
    INVALID_PARAMETER("INVALID_PARAMETER", "Invalid request parameter"),
    USER_ALREADY_EXISTS("USER_ALREADY_EXISTS", "Username already exists"),
    USER_NOT_FOUND("USER_NOT_FOUND", "User does not exist"),
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", "Invalid username or password"),
    AUTH_NOT_LOGIN("AUTH_NOT_LOGIN", "Authentication required"),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", "Access denied"),
    PASSWORD_UNCHANGED("PASSWORD_UNCHANGED", "New password must differ from current password"),
    PASSWORD_CHANGE_REQUIRED("PASSWORD_CHANGE_REQUIRED", "Password change is required");

    private final String errorCode;
    private final String errorMessage;

    UserErrorCode(String errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}

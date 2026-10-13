package cn.lwccxy.trip.system.prompt.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PromptErrorCode {
    PROMPT_NOT_FOUND("PROMPT_NOT_FOUND", "Prompt does not exist"),
    PROMPT_ALREADY_EXISTS("PROMPT_ALREADY_EXISTS", "Prompt key already exists"),
    PROMPT_VERSION_NOT_FOUND("PROMPT_VERSION_NOT_FOUND", "Prompt version does not exist"),
    PROMPT_VERSION_CONTENT_INVALID("PROMPT_VERSION_CONTENT_INVALID", "Prompt version content is invalid"),
    PROMPT_VERSION_NOT_INCREASING("PROMPT_VERSION_NOT_INCREASING", "Version number must be greater than the current maximum"),
    PROMPT_VERSION_CONFLICT("PROMPT_VERSION_CONFLICT", "Version number was used by another request; retry with the latest version"),
    PROMPT_INVALID_STATE("PROMPT_INVALID_STATE", "Prompt version state does not allow this operation"),
    PROMPT_OPERATION_REASON_REQUIRED("PROMPT_OPERATION_REASON_REQUIRED", "Operation reason is required"),
    PROMPT_OPERATOR_REQUIRED("PROMPT_OPERATOR_REQUIRED", "Authenticated operator identity is required"),
    PROMPT_ROLLBACK_TARGET_INVALID("PROMPT_ROLLBACK_TARGET_INVALID", "Rollback target is not available");

    private final String code;
    private final String message;
}

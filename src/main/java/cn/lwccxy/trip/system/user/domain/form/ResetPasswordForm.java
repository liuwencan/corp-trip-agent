package cn.lwccxy.trip.system.user.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import cn.lwccxy.trip.system.user.validation.ValidPassword;

@Data
public class ResetPasswordForm {
    @ValidPassword
    @ToString.Exclude
    private String temporaryPassword;
    @NotBlank
    @Size(max = 1000)
    private String reason;
}

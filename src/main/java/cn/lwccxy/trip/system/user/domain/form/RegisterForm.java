package cn.lwccxy.trip.system.user.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import cn.lwccxy.trip.system.user.validation.ValidPassword;

@Data
public class RegisterForm {
    @NotBlank
    @jakarta.validation.constraints.Pattern(regexp = "[a-z0-9_]{3,32}")
    private String username;
    @ValidPassword
    @ToString.Exclude
    private String password;
    public void setUsername(String username) { this.username = cn.lwccxy.trip.system.user.service.UserService.normalizeUsername(username); }
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unsupported registration field"); }
}

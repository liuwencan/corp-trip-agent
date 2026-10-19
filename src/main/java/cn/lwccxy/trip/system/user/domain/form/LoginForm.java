package cn.lwccxy.trip.system.user.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class LoginForm {
    @NotBlank
    @jakarta.validation.constraints.Pattern(regexp = "[a-z0-9_]{3,32}")
    private String username;
    @NotBlank
    @Size(min = 6, max = 64)
    @ToString.Exclude
    private String password;
    public void setUsername(String username) { this.username = cn.lwccxy.trip.system.user.service.UserService.normalizeUsername(username); }
}

package cn.lwccxy.trip.system.user.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserQueryForm {
    @Min(1)
    private long pageNo = 1;
    @Min(1)
    @Max(200)
    private long pageSize = 10;
    @Size(max = 32)
    private String keyword;
    @jakarta.validation.constraints.Pattern(regexp = "USER|ADMIN")
    private String role;
}

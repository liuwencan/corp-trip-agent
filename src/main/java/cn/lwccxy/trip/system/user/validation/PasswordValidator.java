package cn.lwccxy.trip.system.user.validation;
import cn.lwccxy.trip.system.user.service.UserService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) { return UserService.validPassword(value); }
}

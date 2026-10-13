package cn.lwccxy.trip.system.prompt.domain;

import cn.lwccxy.trip.system.prompt.domain.form.PromptSaveForm;
import cn.lwccxy.trip.system.prompt.domain.form.PromptVersionSaveForm;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFormValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void promptDefinitionRequiresBusinessFields() {
        PromptSaveForm form = new PromptSaveForm();

        assertThat(validator.validate(form)).hasSize(3);
    }

    @Test
    void promptVersionRequiresPositiveNumberAndReason() {
        PromptVersionSaveForm form = new PromptVersionSaveForm();
        form.setVersionNo(0);
        form.setSystemPrompt("system");

        assertThat(validator.validate(form)).hasSize(2);
    }
}

package cn.lwccxy.trip.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringContextUtilTest {

    @Test
    void retrievesSingletonByTypeAndName() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            context.registerBean("service", Object.class, Object::new);
            context.refresh();

            Object bean = context.getBean("service");
            assertThat(SpringContextUtil.getBean("service")).isSameAs(bean);
            assertThat(SpringContextUtil.getBean("service", Object.class)).isSameAs(bean);
            assertThat(SpringContextUtil.getBean(SpringContextUtil.class))
                    .isSameAs(context.getBean(SpringContextUtil.class));
        }
    }

    @Test
    void createsSeparatePrototypeInstancesAndSelectsAgentByName() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            context.registerBean("masterAgent", TestAgent.class, TestAgent::new,
                    definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.registerBean("childAgent", TestAgent.class, TestAgent::new,
                    definition -> definition.setScope(ConfigurableBeanFactory.SCOPE_PROTOTYPE));
            context.refresh();

            assertThat(SpringContextUtil.getBean("masterAgent", TestAgent.class))
                    .isNotSameAs(SpringContextUtil.getBean("masterAgent", TestAgent.class));
            assertThat(SpringContextUtil.getBean("childAgent", TestAgent.class)).isNotNull();
            assertThatThrownBy(() -> SpringContextUtil.getBean(TestAgent.class))
                    .isInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    @Test
    void preservesSpringLookupErrors() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            context.registerBean("service", Object.class, Object::new);
            context.refresh();

            assertThatThrownBy(() -> SpringContextUtil.getBean("missing"))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> SpringContextUtil.getBean("service", String.class))
                    .isInstanceOf(BeanNotOfRequiredTypeException.class);
        }
    }

    @Test
    void clearsContextOnShutdownAndSupportsRestart() {
        try (AnnotationConfigApplicationContext context = newContext()) {
            context.refresh();
        }

        assertThatThrownBy(() -> SpringContextUtil.getBean("service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未初始化或已经关闭");

        try (AnnotationConfigApplicationContext context = newContext()) {
            context.refresh();
            assertThat(SpringContextUtil.getBean(SpringContextUtil.class))
                    .isSameAs(context.getBean(SpringContextUtil.class));
        }
    }

    @Test
    void closingOldContextDoesNotClearNewContext() {
        try (AnnotationConfigApplicationContext oldContext = newContext();
             AnnotationConfigApplicationContext newContext = newContext()) {
            oldContext.refresh();
            newContext.refresh();
            oldContext.close();

            assertThat(SpringContextUtil.getBean(SpringContextUtil.class))
                    .isSameAs(newContext.getBean(SpringContextUtil.class));
        }
    }

    private AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(SpringContextUtil.class);
        return context;
    }

    private static class TestAgent {
    }
}

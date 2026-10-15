package cn.lwccxy.trip.common.util;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 通过 Spring 容器获取 Bean，每次查询均保留 Bean 原有的作用域语义。
 */
@Component
public final class SpringContextUtil implements ApplicationContextAware, DisposableBean {

    private static volatile ApplicationContext applicationContext;

    private ApplicationContext owningContext;

    @Override
    public void setApplicationContext(ApplicationContext context) {
        synchronized (SpringContextUtil.class) {
            owningContext = context;
            applicationContext = context;
        }
    }

    public static <T> T getBean(Class<T> requiredType) {
        return requireContext().getBean(requiredType);
    }

    public static Object getBean(String beanName) {
        return requireContext().getBean(beanName);
    }

    public static <T> T getBean(String beanName, Class<T> requiredType) {
        return requireContext().getBean(beanName, requiredType);
    }

    private static ApplicationContext requireContext() {
        ApplicationContext context = applicationContext;
        if (context == null) {
            throw new IllegalStateException("Spring 容器尚未初始化或已经关闭，无法获取 Bean");
        }
        return context;
    }

    @Override
    public void destroy() {
        synchronized (SpringContextUtil.class) {
            // 旧容器关闭时，不清除后来启动的容器引用。
            if (applicationContext == owningContext) {
                applicationContext = null;
            }
            owningContext = null;
        }
    }
}

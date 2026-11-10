package com.company.policy.common.config;

import com.company.policy.common.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册应用接口拦截器，具体校验逻辑由拦截器承担。 */
@Configuration
public class LoginWebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;

    public LoginWebConfig(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).addPathPatterns("/api/**");
    }
}

package com.jixiejia.agent.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 要求调用者具备指定角色。可标在类或方法上，方法上的声明优先。
 *
 * <p>配合 {@link AuthInterceptor} 使用。默认策略是"所有 /api/** 都要登录"，
 * 本注解是在"已登录"之上<b>追加</b>的角色要求，不是用来开启登录校验的开关——
 * 这样新增接口时忘记加注解只会是"普通用户也能访问"，
 * 而不是更危险的"任何人都能访问"。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 需要的角色标识，取值见 {@link CurrentUser#ROLE_ADMIN} 等。 */
    String[] value();
}

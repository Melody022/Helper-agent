package com.jixiejia.agent.auth;

/**
 * 把当前登录用户绑定到处理线程上，供各层读取。
 *
 * <p>用 ThreadLocal 而不是往每个方法签名里塞 CurrentUser：工具层、审计层都要用到
 * "是谁在操作"，一路传参会污染大量与鉴权无关的签名。
 *
 * <p>必须在请求结束时 {@link #clear()}：线程池会复用线程，
 * 漏清会导致下一个请求读到上一个用户的身份，这是典型的越权漏洞。
 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 当前登录用户，未登录时为 null。 */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 当前登录用户 id，未登录时为 null。 */
    public static Long userId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static boolean isAdmin() {
        CurrentUser user = HOLDER.get();
        return user != null && user.isAdmin();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

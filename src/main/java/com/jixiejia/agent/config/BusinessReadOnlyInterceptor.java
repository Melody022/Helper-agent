package com.jixiejia.agent.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * jxj 业务表（jxb_* / cms_* / ums_* / biz_* / sys_*）只读护栏。
 *
 * <p>这些表属于 RuoYi 那套老系统，本 Agent 只应查询、不应写入。靠"约定"约束不住——
 * Agent 的工具是运行时拼条件的，一次手滑就是脏数据。这里在 Executor#update 上拦截，
 * 凡是 mapped statement 落在 {@code persistence.mapper.jxj} 下的写操作一律直接抛异常。
 *
 * <p>例外：M7 发布工作流要往 jxb_chuzu / jxb_qiuzu 落待审记录，那是受控写入，
 * 走 {@code persistence.mapper.publish} 包，不在本护栏范围内。
 */
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
public class BusinessReadOnlyInterceptor implements Interceptor {

    /** 只读业务 Mapper 所在包，与 persistence.mapper.jxj 保持一致。 */
    private static final String READ_ONLY_PACKAGE = "com.jixiejia.agent.persistence.mapper.jxj.";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        String id = statement.getId();
        if (id != null && id.startsWith(READ_ONLY_PACKAGE)) {
            throw new UnsupportedOperationException(
                    "jxj 业务表为只读，禁止通过只读 Mapper 写入：" + id
                            + "。若确需写入（如发布落库），请放到 persistence.mapper.publish 包下。");
        }
        return invocation.proceed();
    }
}

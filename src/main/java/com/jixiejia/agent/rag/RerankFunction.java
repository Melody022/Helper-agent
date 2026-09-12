package com.jixiejia.agent.rag;

import java.util.List;

/**
 * 精排打分的能力契约。
 *
 * <p>单独抽成接口是为了让证据闸不依赖具体的实现类：测试里可以塞一个返回固定分数的
 * 假实现，完全不碰外部接口，就能把证据闸的判定逻辑单独测清楚。
 */
@FunctionalInterface
public interface RerankFunction {

    /**
     * 给候选资料逐个打分。
     *
     * @return 与 {@code documents} 一一对应的分数；拿不到分时返回 {@code null}
     */
    List<Double> scoreAll(String query, List<String> documents);

    /** 是否配置齐全、可以调用。缺 api-key 时返回 false，证据闸据此走兜底判据。 */
    default boolean configured() {
        return true;
    }
}

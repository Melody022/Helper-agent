package com.jixiejia.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 诊断：为什么"挖掘机生命周期可能出现什么危险因素"答不上来。
 *
 * <p>关掉自评跑一遍，把每一环都打出来：检索命中了什么、证据闸放没放行、
 * 生成出来的答案是什么。用来区分"召回不行"和"自评误杀"。
 */
@SpringBootTest(properties = "rag.self-eval.enabled=false")
class HazardDiagTest {

    private static final String Q = "挖掘机生命周期可能出现什么危险因素";

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private EvidenceGate gate;

    @Autowired
    private KnowledgeAnswerService answerService;

    @Test
    @DisplayName("诊断：关掉自评后看整条链路")
    void diagnose() {
        System.out.println("\n========== 问题：" + Q + " ==========");

        List<KnowledgeRetriever.Hit> hits = retriever.retrieve(Q, 5);
        System.out.println("\n---- 检索命中 " + hits.size() + " 条 ----");
        for (KnowledgeRetriever.Hit h : hits) {
            System.out.printf("  向量 %.4f | 表格id=%s | %s%n     正文: %s%n",
                    h.vectorScore(), h.tableId(),
                    h.title() == null ? "(无标题)" : abbreviate(h.title(), 50),
                    abbreviate(h.content() == null ? "" : h.content().replace("\n", " "), 110));
        }

        EvidenceGate.Verdict v = gate.evaluate(Q, hits, true);
        System.out.printf("%n---- 证据闸：%s ----%n  理由：%s%n",
                v.passed() ? "放行" : "拦下", v.reason());

        System.out.println("\n---- 生成的答案（自评已关闭）----");
        KnowledgeAnswerService.Answer a = answerService.answer(Q, "diag-hazard");
        System.out.println("  answered=" + a.answered());
        System.out.println("  note=" + a.note());
        System.out.println("  text=" + a.text());
        System.out.println("==========================================\n");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

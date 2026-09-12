package com.jixiejia.agent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiFlywheelCandidate;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.mapper.ai.AiFlywheelCandidateMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeChunkMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 知识线测试：入库 → 混合召回 → 证据闸 → 生成 → 自评 → 飞轮。
 *
 * <p>分两类：
 * <ul>
 *   <li><b>纯逻辑</b>（证据闸、飞轮查重标准化）——可以精确断言，不依赖外部服务；</li>
 *   <li><b>依赖 ES</b>——用 {@link Assumptions#assumeTrue} 做前置判断，
 *       ES 没启动时跳过而不是报红。这类测试的失败要能区分"代码坏了"和"环境没起"，
 *       否则每次没开 ES 都是一片红，很快就会没人看测试结果。</li>
 * </ul>
 */
@SpringBootTest
class M6KnowledgeTest {

    private static final String TEST_SOURCE_PREFIX = "test-";

    @Autowired
    private KnowledgeIndex knowledgeIndex;

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private KnowledgeAnswerService answerService;

    @Autowired
    private EvidenceGate evidenceGate;

    @Autowired
    private FlywheelService flywheelService;

    @Autowired
    private AiKnowledgeDocMapper docMapper;

    @Autowired
    private AiKnowledgeChunkMapper chunkMapper;

    @Autowired
    private AiFlywheelCandidateMapper flywheelMapper;

    @Autowired
    private ElasticsearchClient es;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String testSourceId;

    @BeforeEach
    void setUp() {
        testSourceId = TEST_SOURCE_PREFIX + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() throws Exception {
        // 测试会往 MySQL 与 ES 两边写，两边都要清，否则知识库里会积攒测试数据、
        // 甚至影响后续检索结果。
        //
        // ⚠️ 顺序很关键：必须**先**从 MySQL 读出 docId、**再**删。反过来先物理删了 MySQL，
        // 下面这个 selectList 就什么都查不到，循环体一次都不执行——ES 里的测试文档
        // 一条都清不掉，还能被检索到、挤掉正确答案。（修复前正是这个顺序，
        // 索引里积了十几条孤儿 [测试] 文档。）
        List<AiKnowledgeDoc> docs = docMapper.selectList(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                .likeRight(AiKnowledgeDoc::getSourceId, TEST_SOURCE_PREFIX));
        for (AiKnowledgeDoc doc : docs) {
            chunkMapper.delete(Wrappers.<com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk>
                    lambdaQuery().eq(com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk::getDocId, doc.getId()));
        }
        if (knowledgeIndex.available()) {
            es.deleteByQuery(d -> d.index(KnowledgeIndex.NAME)
                    .query(q -> q.terms(t -> t.field(KnowledgeIndex.fieldDocId())
                            .terms(v -> v.value(docs.stream()
                                    .map(doc -> co.elastic.clients.elasticsearch._types.FieldValue
                                            .of(doc.getId()))
                                    .toList())))));
            // delete_by_query 是近实时的：不立刻 refresh 的话，最后一条测试留下的文档
            // 可能还没提交完就被下一轮（或下一次评估）读到。实测跑完整个测试套件后，
            // 索引里就是会残留最后一条，所以这里显式刷一次。
            es.indices().refresh(r -> r.index(KnowledgeIndex.NAME));
        }
        // 物理删除。用 docMapper.deleteById 走的是 @TableLogic 逻辑删除，
        // 只把 del_flag 置成 '2'，行还留在表里；而上面的 selectList 会自动过滤掉它们，
        // 于是下一轮清理根本看不见这些残行——越积越多，还会污染检索结果。
        // （ai_user 那边踩过一模一样的坑，这里忘了改。）
        jdbcTemplate.update("DELETE FROM ai_knowledge_doc WHERE LEFT(source_id, 5) = 'test-'");
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk WHERE doc_id NOT IN (SELECT id FROM ai_knowledge_doc)");

        // 最后再对一次账：把 ES 里"MySQL 已经没有对应文档"的切片全部清掉。
        //
        // 为什么不靠上面那条按 docId 的 deleteByQuery 收尾就够了：实测即使显式 refresh，
        // 偶尔仍会漏下最后一条（入库与清理在异步线程池里交错）。而漏下的切片会真的
        // 挤掉检索结果，不能当小事。对账走的是与线上同一套逻辑，顺便也把这段代码
        // 覆盖到了——比再加一层 sleep 靠谱。
        if (knowledgeIndex.available()) {
            ingestionService.reconcileIndex();
        }

        flywheelMapper.delete(Wrappers.<AiFlywheelCandidate>lambdaQuery()
                .likeRight(AiFlywheelCandidate::getQuestion, "[测试]"));
    }

    // ---------------- 纯逻辑：证据闸（精排不可用时退回向量判据） ----------------

    /**
     * 这一组测的是<b>兜底路径</b>：精排不可用时退回向量判据的行为。
     *
     * <p>精排那条主路径需要真实调用外部接口，放在
     * {@link com.jixiejia.agent.eval.RetrievalEvalTest} 里对着标注集评估——
     * 在那里能算出误拦率/漏放率，在这里单测一条断言不了什么。
     */
    @Test
    @DisplayName("证据闸：没有资料就拦下")
    void gateBlocksWhenNoEvidence() {
        EvidenceGate.Verdict verdict = evidenceGate.evaluate("随便问问", List.of(), true);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).contains("没有检索到任何资料");
        assertThat(verdict.topScore()).isZero();
    }

    @Test
    @DisplayName("证据闸：精排关掉时退回向量判据——向量分太低就拦下")
    void gateBlocksWeakEvidence() {
        // 显式构造一个"精排不可用"的闸，测兜底路径。
        // 用 Spring 注入的那个测不了：它带真实精排，走的是主判据那条分支。
        EvidenceGate fallbackGate = new EvidenceGate((q, docs) -> null, 0.6, 0.19, true);

        EvidenceGate.Verdict verdict = fallbackGate.evaluate("随便问问", List.of(hit(0.2)), true);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.rerankScore()).isNegative();
        assertThat(verdict.reason()).contains("精排不可用");
    }

    @Test
    @DisplayName("证据闸：精排分压过向量分——向量很高但精排很低时也必须拦下")
    void gateBlocksWhenRerankSaysIrrelevant() {
        // 这正是原实现漏放的那类问题：向量相似度看着挺高，其实没有一条资料回答了它
        EvidenceGate gate = new EvidenceGate(rerankReturning(0.05), 0.6, 0.19, true);

        EvidenceGate.Verdict verdict = gate.evaluate("平台的火箭发射服务怎么预约",
                List.of(hit(0.77)), true);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.rerankScore()).isEqualTo(0.05);
        assertThat(verdict.reason()).contains("精排判定没有可用资料");
    }

    @Test
    @DisplayName("证据闸：精排分够高就放行，哪怕向量分不高")
    void gatePassesOnRerankScore() {
        EvidenceGate gate = new EvidenceGate(rerankReturning(0.55), 0.6, 0.19, true);

        EvidenceGate.Verdict verdict = gate.evaluate("押金怎么退", List.of(hit(0.5)), true);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.rerankScore()).isEqualTo(0.55);
    }

    @Test
    @DisplayName("证据闸：精排拿不到分时退回向量判据，而不是直接拒答")
    void gateFallsBackWhenRerankUnavailable() {
        // 返回 null 代表"这次没拿到精排分"（超时/限流/没配 key）
        EvidenceGate gate = new EvidenceGate((q, docs) -> null, 0.6, 0.19, true);

        EvidenceGate.Verdict verdict = gate.evaluate("押金怎么退", List.of(hit(0.85)), true);

        assertThat(verdict.passed()).as("精排挂了不该让知识问答整条不可用").isTrue();
        assertThat(verdict.rerankScore()).isNegative();
        assertThat(verdict.reason()).contains("精排不可用");
    }

    @Test
    @DisplayName("证据闸：关掉开关时一律放行")
    void gateDisabledPassesEverything() {
        assertThat(evidenceGate.evaluate("随便问问", List.of(), false).passed()).isTrue();
    }

    /** 构造一个固定返回某个精排分的打分器，用来隔离测试证据闸的判定逻辑。 */
    private static com.jixiejia.agent.rag.RerankFunction rerankReturning(double score) {
        return (query, documents) -> documents.stream().map(d -> score).toList();
    }

    private static KnowledgeRetriever.Hit hit(double vectorScore) {
        return new KnowledgeRetriever.Hit("v1", 1L, 1L, "标题", "内容",
                0.01, vectorScore, 1.0, 1, 1);
    }

    // ---------------- 纯逻辑：飞轮查重 ----------------

    @Test
    @DisplayName("飞轮：标点与语气词差异能被抹平，同一问题归成一条")
    void flywheelNormalizeMergesSameQuestion() {
        String base = FlywheelService.normalize("押金怎么算");

        assertThat(FlywheelService.normalize("押金怎么算？")).isEqualTo(base);
        assertThat(FlywheelService.normalize("请问押金怎么算呢")).isEqualTo(base);
        assertThat(FlywheelService.normalize("押金怎么算！")).isEqualTo(base);

        // 不同的问题不能被合并
        assertThat(FlywheelService.normalize("手续费怎么算")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("飞轮查重的边界：换了说法但意思相同的，标准化合并不了")
    void flywheelNormalizeLimitation() {
        // 这一条是记录当前实现的边界，不是期望行为：
        // 标准化只能抹掉标点和语气词，抹不掉"是""怎样/怎么"这类换说法。
        // 要合并这类问题得靠向量相似度，属于后续优化。
        String a = FlywheelService.normalize("押金怎么算");
        String b = FlywheelService.normalize("押金是怎么算的");

        assertThat(a).isNotEqualTo(b);
    }

    // ---------------- 依赖 ES 的链路 ----------------

    @Test
    @DisplayName("入库：切块、落库、建索引，重复入库按内容指纹跳过")
    void ingestionIsIdempotent() throws Exception {
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        String question = "[测试] 平台的保证金什么时候退";
        String answer = "保证金在订单完成后三个工作日内原路退回。";

        ingestionService.ingestFaq(question, answer, testSourceId);

        AiKnowledgeDoc doc = docMapper.selectOne(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                .eq(AiKnowledgeDoc::getSourceId, testSourceId));
        assertThat(doc).isNotNull();
        assertThat(doc.getStatus()).isEqualTo("INDEXED");
        assertThat(doc.getChunkCount()).isPositive();

        Long chunks = chunkMapper.selectCount(Wrappers
                .<com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk>lambdaQuery()
                .eq(com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk::getDocId, doc.getId()));
        assertThat(chunks).isPositive();

        // 再灌一次同样的内容：内容指纹没变，应当跳过，切片数不翻倍
        ingestionService.ingestFaq(question, answer, testSourceId);
        Long chunksAgain = chunkMapper.selectCount(Wrappers
                .<com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk>lambdaQuery()
                .eq(com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk::getDocId, doc.getId()));
        assertThat(chunksAgain).isEqualTo(chunks);
    }

    @Test
    @DisplayName("检索 + 回答：知识库里有答案时能答上，且回答基于资料")
    void answersWhenKnowledgeExists() throws Exception {
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        String question = "[测试] 平台的保证金什么时候退";
        ingestionService.ingestFaq(question, "保证金在订单完成后三个工作日内原路退回。", testSourceId);

        // ES 是近实时的，写入后要等一个刷新周期才可搜
        Thread.sleep(1200);

        List<KnowledgeRetriever.Hit> hits = retriever.retrieve(question, 5);
        assertThat(hits).as("刚入库的内容应当能被检索到").isNotEmpty();

        KnowledgeAnswerService.Answer answer = answerService.answer(question, "test-conv");
        System.out.println("[知识回答]\n" + answer.text());

        assertThat(answer.text()).isNotBlank();
        assertThat(answer.topScore()).isPositive();
    }

    @Test
    @DisplayName("真跑：语义召回能跨过「手续费→服务费」的同义词差异")
    void builtinFaqIsRetrievable() throws Exception {
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        // 内置语料是随应用发布的正式内容，不是测试数据，这里显式灌一遍保证存在
        ingestionService.ingestBuiltinFaq();
        Thread.sleep(1200);

        // ① 用确定的证据证明"混合召回有必要"：
        // 纯关键词（BM25）搜"手续费"**一条都匹配不到**——语料里写的是"服务费"。
        // 这一步不依赖排名，也不受其它测试数据影响。
        var bm25Only = es.search(q -> q.index(KnowledgeIndex.NAME).size(5)
                        .query(b -> b.match(m -> m.field(KnowledgeIndex.fieldText()).query("手续费"))),
                Map.class);
        assertThat(bm25Only.hits().hits())
                .as("关键词路搜「手续费」应当匹配不到——正因为如此才需要向量召回")
                .isEmpty();

        // ② 向量路能把它捞回来（这是混合召回的价值所在）。
        // 召回取大一点：同类用例可能刚往库里写过测试文档，而 ES 删除是近实时的，
        // 上一轮的清理未必已经生效，残留数据会把正确答案挤出小窗口。
        List<KnowledgeRetriever.Hit> hits = retriever.retrieve("平台收多少手续费", 20)
                .stream()
                .filter(h -> h.content() != null && !h.content().startsWith("问：[测试]"))
                .toList();

        assertThat(hits.stream().map(KnowledgeRetriever.Hit::content).toList())
                .as("向量召回应当能把「服务费」那条捞回来，这是混合召回的意义")
                .anyMatch(content -> content.contains("服务费"));
    }

    /**
     * 说明：这一组<b>只断言到检索与证据闸</b>，不断言最终"答没答上来"。
     *
     * <p>因为最后一道自评用的是本地 7B 小模型，判断本身不稳定——同一个问题、
     * 同一份资料，它会时而判通过、时而判不通过（实测）。
     * 把断言建在这个随机组件上，测试就会变成抽奖，红绿都不说明问题。
     * 自评的不稳定是<b>已知问题</b>，见 docs/待办-评测与量化.md。
     */

    /**
     * ⚠️ 这条用例曾经因为证据闸阈值形同虚设而 {@code @Disabled}：
     * 知识库有内容之后，一个完全不相关的问题（"平台的火箭发射服务怎么预约"）
     * 向量相关度能拿到 0.7655，远超当时默认的 0.6，闸直接放行。
     *
     * <p>现在闸的主判据换成了精排分（见 {@link EvidenceGate}），这个问题在精排下只有
     * 0.16 左右，会被稳定拦下，不再依赖时灵时不灵的自评。
     *
     * <p>这也是这条用例能重新启用的意义：它验证的是"闸真的拦得住"，
     * 而不是"自评这次碰巧判对了"。所以它<b>必须走真实的精排链路</b>，
     * 不能把这个类配成 {@code rag.rerank.enabled=false}——那样测的就成了兜底路径。
     */
    @Test
    @DisplayName("兜底：知识库里没有的内容必须拒绝作答，并记进飞轮")
    void refusesAndRecordsWhenKnowledgeMissing() {
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        String question = "[测试] 平台的火箭发射服务怎么预约";
        KnowledgeAnswerService.Answer answer = answerService.answer(question, "test-conv");

        assertThat(answer.answered())
                .as("知识库里没有的内容不该给出答案")
                .isFalse();
        assertThat(answer.text()).contains("转人工");

        // 进飞轮待人工补
        List<AiFlywheelCandidate> candidates = flywheelMapper.selectList(
                Wrappers.<AiFlywheelCandidate>lambdaQuery()
                        .likeRight(AiFlywheelCandidate::getQuestion, "[测试] 平台的火箭发射"));
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("飞轮：同一个问题重复出现只留一条候选")
    void flywheelDeduplicates() {
        String question = "[测试] 重复提问会怎么处理";
        assertThat(flywheelService.record(FlywheelService.SOURCE_LOW_CONFIDENCE,
                "c1", question, null, 0.1)).isTrue();

        // 只有标点不同，标准化之后是同一条，不该再插
        assertThat(flywheelService.record(FlywheelService.SOURCE_LOW_CONFIDENCE,
                "c2", "[测试] 重复提问会怎么处理？", null, 0.1)).isFalse();

        Long count = flywheelMapper.selectCount(Wrappers.<AiFlywheelCandidate>lambdaQuery()
                .likeRight(AiFlywheelCandidate::getQuestion, "[测试] 重复提问"));
        assertThat(count).isEqualTo(1);
    }
}

package com.jixiejia.agent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.entity.jxj.CmsArticle;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeChunkMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import com.jixiejia.agent.persistence.mapper.jxj.CmsArticleMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import com.jixiejia.agent.tool.ToolSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识入库：把文章变成可检索的切片。
 *
 * <p>流程是"解析 → 切片 → 算向量 → 落库 → 建索引"，其中两个设计点值得说明：
 *
 * <p><b>一、MySQL 和 ES 都存，但职责不同。</b>
 * MySQL 的 ai_knowledge_doc/chunk 存权威原文，ES 存"用来检索的副本（正文 + 向量）"。
 * 之所以不只在 ES 里存一份：ES 会因为换分词器、调索引结构而需要重建，
 * 那时必须能只靠数据库重新灌一遍。搜索引擎是索引，不是数据源。
 *
 * <p><b>二、按内容指纹跳过没变的文章。</b>
 * 每次入库都重新切片、重新调一遍 embedding，13 篇文章就是上百次调用。
 * 内容哈希没变就整篇跳过，重复执行几乎是零成本，这样"入库"可以放心地反复跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    /** 每批算多少个切片的向量。太大容易触发接口的批量上限，太小浪费往返。 */
    private static final int EMBED_BATCH = 16;

    /** 内置平台规则语料的位置 */
    private static final String BUILTIN_FAQ_PATH = "classpath:knowledge/platform-faq.md";

    private final CmsArticleMapper articleMapper;
    private final AiKnowledgeDocMapper docMapper;
    private final AiKnowledgeChunkMapper chunkMapper;
    private final TextChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient es;
    private final KnowledgeIndex knowledgeIndex;
    private final ResourceLoader resourceLoader;

    @Value("${rag.embedding-model:text-embedding-v4}")
    private String embeddingModelName;

    /** 入库结果，供管理接口返回。 */
    public record IngestionReport(int scanned, int ingested, int skipped, int chunks, List<String> failures) {
    }

    /** 把平台上已发布的文章增量入库。可反复执行。 */
    public IngestionReport ingestArticles() {
        if (!knowledgeIndex.available()) {
            return new IngestionReport(0, 0, 0, 0, List.of("Elasticsearch 不可用，未执行入库"));
        }

        List<CmsArticle> articles = articleMapper.selectList(BizFilters.visibleArticle());
        int ingested = 0;
        int skipped = 0;
        int totalChunks = 0;
        List<String> failures = new ArrayList<>();

        for (CmsArticle article : articles) {
            try {
                int chunks = ingestOne(article);
                if (chunks < 0) {
                    skipped++;
                } else {
                    ingested++;
                    totalChunks += chunks;
                }
            } catch (Exception e) {
                log.warn("文章 {} 入库失败", article.getId(), e);
                failures.add("文章 " + article.getId() + "：" + e.getMessage());
                markFailed(article, e);
            }
        }

        log.info("知识入库完成：扫描 {} 篇，新入库 {} 篇，跳过 {} 篇，共 {} 个切片",
                articles.size(), ingested, skipped, totalChunks);
        return new IngestionReport(articles.size(), ingested, skipped, totalChunks, failures);
    }

    /**
     * 入库单篇文章。
     *
     * @return 切片数；内容没变而跳过时返回 -1
     */
    private int ingestOne(CmsArticle article) throws Exception {
        String text = ToolSupport.stripHtml(article.getDesc());
        if (text == null || text.isBlank()) {
            // 库里有多篇文章正文其实只有一张图，剥完标签就是空的，跳过而不是存个空壳
            log.debug("文章 {} 正文为空（可能只有图片），跳过", article.getId());
            return -1;
        }

        AiKnowledgeDoc doc = upsertDoc("article", String.valueOf(article.getId()),
                article.getTitle(), text);
        return doc == null ? -1 : indexDocument(doc, text);
    }

    /**
     * 把一条人工审核通过的问答补进知识库（飞轮的出口）。
     *
     * <p>存成"问：… 答：…"的格式而不是只存答案：检索时用户问的是问题，
     * 问题本身是重要的匹配依据，丢掉它会让这条知识很难被召回。
     */
    public void ingestFaq(String question, String answer, String sourceId) throws Exception {
        if (!knowledgeIndex.available()) {
            throw new IllegalStateException("Elasticsearch 不可用，无法补进知识库");
        }
        String text = "问：" + question + "\n答：" + answer;
        String title = question.length() <= 100 ? question : question.substring(0, 100);
        AiKnowledgeDoc doc = upsertDoc("faq", sourceId, title, text);
        if (doc != null) {
            indexDocument(doc, text);
        }
    }

    /**
     * 找或建一条文档记录。
     *
     * @return 需要重建索引的文档；内容没变时返回 null 表示跳过
     */
    private AiKnowledgeDoc upsertDoc(String docType, String sourceId, String title, String text) {
        String hash = TextChunker.sha256(text);

        AiKnowledgeDoc existing = docMapper.selectOne(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                .eq(AiKnowledgeDoc::getDocType, docType)
                .eq(AiKnowledgeDoc::getSourceId, sourceId));

        if (existing != null && hash.equals(existing.getContentHash())
                && "INDEXED".equals(existing.getStatus())) {
            return null;
        }

        AiKnowledgeDoc doc = existing != null ? existing : new AiKnowledgeDoc();
        doc.setDocType(docType);
        doc.setSourceId(sourceId);
        doc.setTitle(title);
        doc.setContent(text);
        doc.setContentHash(hash);
        doc.setStatus("PARSED");
        doc.setDelFlag("0");

        if (doc.getId() == null) {
            docMapper.insert(doc);
        } else {
            docMapper.updateById(doc);
        }
        return doc;
    }

    /** 切片 → 算向量 → 落库 → 建索引。文档内容变了会先清掉旧切片再重建。 */
    private int indexDocument(AiKnowledgeDoc doc, String text) throws Exception {
        removeChunks(doc.getId());

        List<TextChunker.Chunk> chunks = chunker.chunk(text);
        if (chunks.isEmpty()) {
            doc.setStatus("FAILED");
            doc.setErrorMsg("切片结果为空");
            docMapper.updateById(doc);
            return 0;
        }

        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<TextChunker.Chunk> batch = chunks.subList(i, Math.min(chunks.size(), i + EMBED_BATCH));
            List<float[]> vectors = embeddingModel.embed(batch.stream().map(TextChunker.Chunk::content).toList());

            for (int j = 0; j < batch.size(); j++) {
                saveChunk(doc, batch.get(j), vectors.get(j));
            }
        }

        doc.setChunkCount(chunks.size());
        doc.setStatus("INDEXED");
        doc.setErrorMsg(null);
        docMapper.updateById(doc);
        return chunks.size();
    }

    /** 一个切片：MySQL 存原文，ES 存副本 + 向量。 */
    private void saveChunk(AiKnowledgeDoc doc, TextChunker.Chunk chunk, float[] vector) throws Exception {
        AiKnowledgeChunk row = new AiKnowledgeChunk();
        row.setDocId(doc.getId());
        row.setChunkIndex(chunk.index());
        row.setContent(chunk.content());
        row.setContentHash(chunk.hash());
        row.setCharCount(chunk.content().length());
        row.setEmbeddingModel(embeddingModelName);
        row.setVectorId(KnowledgeIndex.vectorId(doc.getId(), chunk.index()));
        chunkMapper.insert(row);

        Map<String, Object> esDoc = new LinkedHashMap<>();
        esDoc.put(KnowledgeIndex.fieldDocId(), doc.getId());
        esDoc.put(KnowledgeIndex.fieldChunkId(), row.getId());
        esDoc.put(KnowledgeIndex.fieldTitle(), doc.getTitle());
        esDoc.put(KnowledgeIndex.fieldText(), chunk.content());
        esDoc.put(KnowledgeIndex.fieldVector(), toFloatList(vector));

        es.index(idx -> idx
                .index(KnowledgeIndex.NAME)
                .id(row.getVectorId())
                .document(esDoc));
    }

    /**
     * 把内置的平台规则语料灌进知识库。
     *
     * <p>语料放在 {@code classpath:knowledge/platform-faq.md}，按 {@code ## 标题} 切分，
     * 一条规则 = 一个知识文档。之所以用文件而不是直接写数据库脚本：
     * 入库要做切片、算向量、建索引，这些只有入库服务会做；
     * 而且文件方式可以随时改了重新灌，内容变了会被指纹识别出来重建。
     */
    public IngestionReport ingestBuiltinFaq() {
        if (!knowledgeIndex.available()) {
            return new IngestionReport(0, 0, 0, 0, List.of("Elasticsearch 不可用，未执行入库"));
        }

        List<KnowledgeSeed> seeds = loadBuiltinFaq();
        int ingested = 0;
        int skipped = 0;
        int totalChunks = 0;
        List<String> failures = new ArrayList<>();

        for (KnowledgeSeed seed : seeds) {
            try {
                AiKnowledgeDoc doc = upsertDoc("faq", seed.sourceId(), seed.title(), seed.content());
                if (doc == null) {
                    skipped++;
                } else {
                    ingested++;
                    totalChunks += indexDocument(doc, seed.content());
                }
            } catch (Exception e) {
                log.warn("内置规则「{}」入库失败", seed.title(), e);
                failures.add(seed.title() + "：" + e.getMessage());
            }
        }

        log.info("内置规则入库完成：新增 {} 条，跳过 {} 条，共 {} 个切片", ingested, skipped, totalChunks);
        return new IngestionReport(seeds.size(), ingested, skipped, totalChunks, failures);
    }

    /** 一条待入库的语料。 */
    private record KnowledgeSeed(String sourceId, String title, String content) {
    }

    /** 解析内置语料文件：按 {@code ## 标题} 切块，标题下的正文归该条。 */
    private List<KnowledgeSeed> loadBuiltinFaq() {
        Resource resource = resourceLoader.getResource(BUILTIN_FAQ_PATH);
        if (!resource.exists()) {
            log.warn("内置规则文件不存在：{}", BUILTIN_FAQ_PATH);
            return List.of();
        }

        List<KnowledgeSeed> seeds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String currentTitle = null;
            StringBuilder body = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    addSeed(seeds, currentTitle, body);
                    currentTitle = line.substring(3).trim();
                    body.setLength(0);
                } else if (currentTitle != null) {
                    body.append(line).append('\n');
                }
                // 第一个标题之前的内容是文件说明，忽略
            }
            addSeed(seeds, currentTitle, body);

        } catch (Exception e) {
            log.error("读取内置规则文件失败", e);
            return List.of();
        }
        return seeds;
    }

    private void addSeed(List<KnowledgeSeed> seeds, String title, StringBuilder body) {
        if (title == null || title.isBlank()) {
            return;
        }
        String content = body.toString().trim();
        if (content.isBlank()) {
            return;
        }
        // sourceId 用序号：稳定、可重复执行，改了正文也不会插出重复条目
        seeds.add(new KnowledgeSeed("builtin-" + (seeds.size() + 1), title, content));
    }

    /** 删掉某文档的所有切片，MySQL 与 ES 两边都清，避免留下查得到但已失效的旧内容。 */
    private void removeChunks(Long docId) throws Exception {
        chunkMapper.delete(Wrappers.<AiKnowledgeChunk>lambdaQuery()
                .eq(AiKnowledgeChunk::getDocId, docId));

        es.deleteByQuery(d -> d
                .index(KnowledgeIndex.NAME)
                .query(q -> q.term(t -> t
                        .field(KnowledgeIndex.fieldDocId())
                        .value(docId))));
    }

    private void markFailed(CmsArticle article, Exception e) {
        try {
            AiKnowledgeDoc doc = docMapper.selectOne(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                    .eq(AiKnowledgeDoc::getDocType, "article")
                    .eq(AiKnowledgeDoc::getSourceId, String.valueOf(article.getId())));
            if (doc != null) {
                doc.setStatus("FAILED");
                doc.setErrorMsg(truncate(e.toString()));
                docMapper.updateById(doc);
            }
        } catch (Exception ignored) {
            // 记录失败状态本身再失败就没辙了，不影响主流程
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 900 ? s : s.substring(0, 900);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }
}

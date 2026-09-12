package com.jixiejia.agent.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.api.dto.FlywheelReviewRequest;
import com.jixiejia.agent.auth.AuthContext;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.auth.RequireRole;
import com.jixiejia.agent.persistence.entity.ai.AiFlywheelCandidate;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.mapper.ai.AiFlywheelCandidateMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import com.jixiejia.agent.rag.KnowledgeFileStore;
import com.jixiejia.agent.rag.KnowledgeIndex;
import com.jixiejia.agent.rag.KnowledgeIngestionService;
import com.jixiejia.agent.rag.TextChunker;
import com.jixiejia.agent.rag.parse.DocumentParseException;
import com.jixiejia.agent.rag.parse.DocumentParser;
import com.jixiejia.agent.rag.parse.ParsedDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库与数据飞轮的管理接口。需要 ADMIN 角色。
 *
 * <p>M9 的 Thymeleaf 管理台会复用这些接口，所以这里只做接口、不做页面。
 */
@Slf4j
@Tag(name = "管理-知识库与飞轮", description = "知识入库、飞轮审核，需 ADMIN")
@RestController
@RequestMapping("/api/admin/knowledge")
@RequireRole(CurrentUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final DocumentParser documentParser;
    private final AiFlywheelCandidateMapper flywheelMapper;
    private final AiKnowledgeDocMapper docMapper;
    private final KnowledgeFileStore fileStore;

    @Operation(summary = "触发知识入库",
            description = "把平台已发布文章与内置规则语料增量灌入知识库，可反复执行")
    @PostMapping("/ingest")
    public ResponseEntity<?> ingest() {
        KnowledgeIngestionService.IngestionReport articles = ingestionService.ingestArticles();
        KnowledgeIngestionService.IngestionReport faq = ingestionService.ingestBuiltinFaq();

        return ResponseEntity.ok(Map.of(
                "articles", Map.of(
                        "scanned", articles.scanned(),
                        "ingested", articles.ingested(),
                        "skipped", articles.skipped(),
                        "chunks", articles.chunks(),
                        "failures", articles.failures()),
                "builtinFaq", Map.of(
                        "scanned", faq.scanned(),
                        "ingested", faq.ingested(),
                        "skipped", faq.skipped(),
                        "chunks", faq.chunks(),
                        "failures", faq.failures())));
    }

    @Operation(summary = "知识库概况", description = "文档数、切片数、ES 是否可用")
    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        Long docs = docMapper.selectCount(
                Wrappers.<AiKnowledgeDoc>lambdaQuery().eq(AiKnowledgeDoc::getStatus, "INDEXED"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexedDocs", docs);
        result.put("esIndexName", KnowledgeIndex.NAME);
        result.put("supportedFileTypes", DocumentParser.supportedExtensions());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "上传文档入库",
            description = "支持 PDF / Word / Excel / PPT / 图片 / md / txt / csv。"
                    + "PDF 与 Office 走本地 LiteParse 解析（快、免费），扫描页才走多模态 OCR（按量计费）")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
                                    @RequestParam(required = false) String title) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "请选择要上传的文件"));
        }

        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            String ext = DocumentParser.extensionOf(filename);

            // sourceId 用内容指纹：同一份文件重复上传会被入库查重跳过，不会堆重复文档。
            // 落盘文件名也用同一个指纹，于是"重复上传"连文件都不会堆第二份。
            String sourceId = "upload-" + TextChunker.sha256(bytes).substring(0, 16);

            // 先把原件落下来。解析要花多久是解析的事，"这份文件传过"这个事实先固化——
            // 后面无论哪一步失败，原件都还在磁盘上，能拿来排查、也能重新入库。
            String filePath = fileStore.store(sourceId, ext, bytes);

            ParsedDocument parsed = documentParser.parse(filename, bytes);

            if (parsed.text().isBlank() && parsed.tables().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400,
                        "message", "这份文档没有解析出任何文字内容"
                                + (parsed.warnings().isEmpty() ? "" : "：" + String.join("；", parsed.warnings()))));
            }

            String docTitle = (title == null || title.isBlank()) ? parsed.title() : title.trim();
            List<String> warnings = new ArrayList<>(parsed.warnings());

            int chunks = ingestionService.ingestUpload(sourceId, docTitle, parsed);
            if (chunks < 0) {
                return ResponseEntity.ok(Map.of("code", 200, "message",
                        "这份文档内容和库里已有的完全一致，已跳过（没有重复入库）"));
            }

            // 预览件：**只有 Office 需要**。PDF/图片/纯文本的原件浏览器本来就能直接显示，
            // 没必要再存一份；docx/xlsx/pptx 渲染不了，不转就只能让用户下载。
            // 转失败只影响"能不能跳回原件"，绝不能影响入库——所以这里不抛异常。
            String previewPath = null;
            if (KnowledgeFileStore.kindOf(ext) == KnowledgeFileStore.PreviewKind.OFFICE) {
                previewPath = fileStore.convertOfficeToPdf(sourceId, filePath);
                if (previewPath == null) {
                    warnings.add("原件是 Office 文档，但 PDF 预览转换失败（可能没装 LibreOffice），"
                            + "这份资料的角标将无法跳转原件");
                }
            }

            writeFileFields(sourceId, filename, filePath, previewPath, bytes.length);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 200);
            body.put("message", "入库成功");
            body.put("title", docTitle);
            body.put("chunks", chunks);
            body.put("tables", parsed.tables().size());
            body.put("pageCount", parsed.pageCount());
            body.put("ocrPages", parsed.ocrPages());
            body.put("warnings", warnings);
            return ResponseEntity.ok(body);

        } catch (DocumentParseException e) {
            // 解析类失败是"用户能看懂并自己修"的（格式不支持、没装 LibreOffice、页数超限），
            // 用 400 而不是 500
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("上传入库失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "入库失败：" + e.getMessage()));
        }
    }

    /**
     * 把原件信息写回文档记录。
     *
     * <p><b>这里必须用 UpdateWrapper 显式 set，不能用 {@code updateById}。</b>
     * MyBatis-Plus 的 {@code updateById} 会**跳过 null 字段**，
     * 于是"把 previewPath 清空"这种操作永远不生效——项目里已经踩过同一个坑
     * （踩坑第 26/27 条，清理令牌时 {@code setXxx(null)} 清不掉）。
     */
    private void writeFileFields(String sourceId, String fileName, String filePath,
                                 String previewPath, long size) {
        docMapper.update(null, Wrappers.<AiKnowledgeDoc>lambdaUpdate()
                .eq(AiKnowledgeDoc::getDocType, "upload")
                .eq(AiKnowledgeDoc::getSourceId, sourceId)
                .set(AiKnowledgeDoc::getFileName, fileName)
                .set(AiKnowledgeDoc::getFilePath, filePath)
                .set(AiKnowledgeDoc::getPreviewPath, previewPath)
                .set(AiKnowledgeDoc::getFileSize, size));
    }

    @Operation(summary = "已入库文档列表", description = "走 MySQL 查询（ES 索引里没存 docType，过滤不了来源）")
    @GetMapping("/docs")
    public ResponseEntity<?> docs(@RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "") String keyword) {
        var query = Wrappers.<AiKnowledgeDoc>lambdaQuery()
                .orderByDesc(AiKnowledgeDoc::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200)));
        if (!keyword.isBlank()) {
            query.like(AiKnowledgeDoc::getTitle, keyword);
        }

        List<Map<String, Object>> rows = docMapper.selectList(query).stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("docType", d.getDocType());
            row.put("title", d.getTitle());
            row.put("sourceId", d.getSourceId());
            row.put("status", d.getStatus());
            row.put("chunkCount", d.getChunkCount());
            row.put("createTime", d.getCreateTime());
            return row;
        }).toList();
        return ResponseEntity.ok(rows);
    }

    @Operation(summary = "删除文档", description = "同时清掉 ES 切片与 MySQL 切片/表格；文档本身逻辑删除")
    @DeleteMapping("/docs/{id}")
    public ResponseEntity<?> deleteDoc(@PathVariable Long id) {
        try {
            boolean deleted = ingestionService.deleteDocument(id);
            if (!deleted) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", 404, "message", "文档不存在：" + id));
            }
            return ResponseEntity.ok(Map.of("code", 200, "message", "已删除，相关切片与表格已从索引中清除"));
        } catch (Exception e) {
            log.error("删除文档失败：{}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "删除失败：" + e.getMessage()));
        }
    }

    @Operation(summary = "索引对账",
            description = "清理 ES 里存在、MySQL 里已无对应文档的孤儿切片。删除是分两步做的，"
                    + "中间失败会留下查得到但已失效的旧内容，需要定期对账")
    @PostMapping("/reconcile")
    public ResponseEntity<?> reconcile() {
        try {
            int removed = ingestionService.reconcileIndex();
            return ResponseEntity.ok(Map.of("code", 200, "removed", removed,
                    "message", removed == 0 ? "没有孤儿，索引与数据库一致" : "已清理 " + removed + " 个孤儿"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "对账失败：" + e.getMessage()));
        }
    }

    @Operation(summary = "飞轮待审列表", description = "系统答不上来的问题，人工补答案后回灌知识库")
    @GetMapping("/flywheel")
    public ResponseEntity<?> flywheel(@RequestParam(defaultValue = "20") int limit) {
        List<AiFlywheelCandidate> pending = flywheelMapper.selectList(
                Wrappers.<AiFlywheelCandidate>lambdaQuery()
                        .eq(AiFlywheelCandidate::getStatus, "PENDING")
                        .orderByDesc(AiFlywheelCandidate::getId)
                        .last("limit " + Math.max(1, Math.min(limit, 100))));

        return ResponseEntity.ok(pending.stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("source", c.getSource());
            row.put("question", c.getQuestion());
            row.put("score", c.getScore());
            row.put("conversationId", c.getConversationId());
            row.put("createTime", c.getCreateTime());
            return row;
        }).toList());
    }

    @Operation(summary = "审核通过", description = "把问题与人工答案一起补进知识库，下次就能答上")
    @PostMapping("/flywheel/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestBody FlywheelReviewRequest request) {
        if (request.answer() == null || request.answer().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "审核通过时必须填写答案，否则补进知识库的是空内容"));
        }

        AiFlywheelCandidate candidate = flywheelMapper.selectById(id);
        if (candidate == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", 404, "message", "候选不存在：" + id));
        }
        if (!"PENDING".equals(candidate.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "该候选已处理过，当前状态：" + candidate.getStatus()));
        }

        try {
            // 先补知识库，成功了再改状态。反过来的话，索引失败就丢掉了一条人工答案
            ingestionService.ingestFaq(candidate.getQuestion(), request.answer(),
                    "flywheel-" + candidate.getId());

            candidate.setStatus("APPROVED");
            candidate.setAnswer(request.answer());
            candidate.setReviewNote(request.note());
            candidate.setReviewerId(AuthContext.userId());
            candidate.setReviewTime(LocalDateTime.now());
            flywheelMapper.updateById(candidate);

            return ResponseEntity.ok(Map.of("code", 200, "message", "已补进知识库，下次可回答"));

        } catch (Exception e) {
            log.error("飞轮回灌知识库失败：{}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "补进知识库失败：" + e.getMessage()));
        }
    }

    @Operation(summary = "审核驳回")
    @PostMapping("/flywheel/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id,
                                    @RequestBody(required = false) FlywheelReviewRequest request) {
        AiFlywheelCandidate candidate = flywheelMapper.selectById(id);
        if (candidate == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", 404, "message", "候选不存在：" + id));
        }

        candidate.setStatus("REJECTED");
        candidate.setReviewNote(request == null ? null : request.note());
        candidate.setReviewerId(AuthContext.userId());
        candidate.setReviewTime(LocalDateTime.now());
        flywheelMapper.updateById(candidate);

        return ResponseEntity.ok(Map.of("code", 200, "message", "已驳回"));
    }
}

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
import com.jixiejia.agent.rag.KnowledgeIndex;
import com.jixiejia.agent.rag.KnowledgeIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
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
    private final AiFlywheelCandidateMapper flywheelMapper;
    private final AiKnowledgeDocMapper docMapper;

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
        return ResponseEntity.ok(result);
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

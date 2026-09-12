package com.jixiejia.agent.api;

import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import com.jixiejia.agent.rag.KnowledgeFileStore;
import com.jixiejia.agent.rag.parse.DocumentParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 答案依据的原件预览。<b>登录即可访问</b>（不是 ADMIN）——聊天页的角标要能点开它。
 *
 * <p><b>为什么不做成"一个可以直接丢给 iframe 的 URL"。</b>
 * 一是 {@code <iframe src>} 带不了 {@code Authorization} 头，会被鉴权拦截器 401 掉；
 * 二是那样就等于给用户一个可以另存、可以转发的直链。前端用 fetch 带令牌取字节、
 * 再喂给 PDF.js，**不暴露直链**——这也正好是"聊天页只预览、不给下载"这个产品决策的落地。
 *
 * <p>返回的内容按类型决定：<b>默认就是原件</b>，只有 Office 文档返回转出来的 PDF
 * （浏览器渲染不了 docx/xlsx/pptx，不转就只能让用户下载）。
 * "该取哪个文件"这条判断收口在 {@link KnowledgeFileStore#previewFileOf}，
 * 和 {@code KnowledgeAnswerService} 里给前端的 {@code previewable} 用的是同一个——
 * 两边各写一遍的话，会出现"前端以为能预览、点开却是 404"。
 */
@Slf4j
@Tag(name = "知识文件", description = "答案依据的原件预览，登录可用")
@RestController
@RequestMapping("/api/knowledge/files")
@RequiredArgsConstructor
public class KnowledgeFileController {

    private final AiKnowledgeDocMapper docMapper;
    private final KnowledgeFileStore fileStore;

    @Operation(summary = "预览原件",
            description = "PDF/图片/纯文本直接返回原件；Office 文档返回转出来的 PDF。一律 inline，不给另存")
    @GetMapping("/{docId}/preview")
    public ResponseEntity<?> preview(@PathVariable Long docId) {
        AiKnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            return notFound("文档不存在：" + docId);
        }

        // 和给前端的 previewable 用同一条规则：能不能预览、该取哪个文件，是同一个决策
        String relative = KnowledgeFileStore.previewFileOf(doc.getFilePath(), doc.getPreviewPath());
        if (relative == null) {
            return notFound("这份资料没有可预览的原件（可能来自平台文章，或原件格式浏览器渲染不了）");
        }

        // 媒体类型按**原件**的扩展名算：Office 走的虽然是 PDF 预览件，但前端的期望是
        // "给我一个能显示的 PDF"，所以这里给 application/pdf 而不是 docx 的类型
        MediaType mediaType = mediaTypeOf(relative, doc.getFilePath());
        if (mediaType == null) {
            return notFound("这份原件的格式不支持预览");
        }

        try {
            Resource resource = new FileSystemResource(fileStore.resolve(relative));
            if (!resource.exists()) {
                return notFound("原件文件不在磁盘上了");
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    // inline：只预览不给另存。注意这里**不能**返回 text/html 之类
                    // 浏览器会当页面渲染的类型，否则上传的文档就成了 XSS 的载体——
                    // 而知识库的内容是用户上传的，不能假设它无害。
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(resource);
        } catch (Exception e) {
            log.error("读取原件失败：{}", relative, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "读取原件失败：" + e.getMessage()));
        }
    }

    /**
     * 决定用哪个 MediaType 返回。
     *
     * @param relative 实际要返回的文件（Office 文档这里是转出来的 PDF）
     * @param originalFilePath 原件路径，用来判断"原件本来是什么类型"
     */
    private static MediaType mediaTypeOf(String relative, String originalFilePath) {
        KnowledgeFileStore.PreviewKind kind =
                KnowledgeFileStore.kindOf(DocumentParser.extensionOf(originalFilePath));
        return switch (kind) {
            // Office 给的是转出来的 PDF，所以类型按 PDF 报
            case PDF, OFFICE -> MediaType.APPLICATION_PDF;
            case IMAGE -> imageType(DocumentParser.extensionOf(relative));
            // 纯文本必须显式带 charset，否则浏览器会按本地默认编码猜，中文会乱码
            case TEXT -> new MediaType("text", "plain", StandardCharsets.UTF_8);
            case UNSUPPORTED -> null;
        };
    }

    private static MediaType imageType(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase()) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> new MediaType("image", "webp");
            case "bmp" -> new MediaType("image", "bmp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private static ResponseEntity<?> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", message));
    }
}

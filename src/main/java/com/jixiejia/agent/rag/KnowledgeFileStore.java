package com.jixiejia.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 知识库原件的落盘与读取。
 *
 * <p><b>为什么文件名用 sourceId（内容指纹）而不是原始文件名。</b>
 * 上传同一份文件两次，内容指纹一样、文件名就一样，很自然地覆盖掉——
 * 不会因为反复上传堆出一堆同样的文件。而两份"内容不同但同名"的文件
 * （比如都叫"规范.pdf"）也不会互相覆盖。去重靠的是内容，不是名字。
 *
 * <p><b>为什么只要 Office 才转 PDF。</b>浏览器的能力边界：
 * PDF 有内置/PDF.js 查看器，图片能直接显示，纯文本能直接显示；
 * 而 docx/xlsx/pptx **没有任何浏览器渲染得了**，只会触发下载。
 * 所以"转 PDF"不是默认动作，是给 Office 补的一个例外。
 */
@Slf4j
@Component
public class KnowledgeFileStore {

    /** 这份原件浏览器能不能直接看 */
    public enum PreviewKind {
        /** 浏览器/PDF.js 能直接渲染 */
        PDF,
        /** 能直接显示 */
        IMAGE,
        /** 纯文本，能直接显示 */
        TEXT,
        /** 浏览器渲染不了，要转 PDF 才能预览 */
        OFFICE,
        /** 不支持预览 */
        UNSUPPORTED
    }

    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final Set<String> TEXT_EXT = Set.of("md", "txt", "csv");
    private static final Set<String> OFFICE_EXT = Set.of("docx", "doc", "xlsx", "xls", "pptx");

    /** 转 PDF 的超时。LibreOffice 冷启动要几秒，大文件更久 */
    private static final long CONVERT_TIMEOUT_SECONDS = 120;

    private final Path root;

    public KnowledgeFileStore(@Value("${rag.upload.dir:data/knowledge-files}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 落盘目录建不出来是启动期就该炸的问题：越晚发现，丢的原始文件越多
            throw new IllegalStateException("无法创建知识库文件目录：" + root, e);
        }
        log.info("知识库原件目录：{}", root);
    }

    public Path root() {
        return root;
    }

    /** 按扩展名判断这份原件能不能被浏览器直接看。 */
    public static PreviewKind kindOf(String ext) {
        if (ext == null || ext.isBlank()) {
            return PreviewKind.UNSUPPORTED;
        }
        String e = ext.toLowerCase();
        if ("pdf".equals(e)) {
            return PreviewKind.PDF;
        }
        if (IMAGE_EXT.contains(e)) {
            return PreviewKind.IMAGE;
        }
        if (TEXT_EXT.contains(e)) {
            return PreviewKind.TEXT;
        }
        if (OFFICE_EXT.contains(e)) {
            return PreviewKind.OFFICE;
        }
        return PreviewKind.UNSUPPORTED;
    }

    /**
     * 把原件落盘。
     *
     * @param sourceId 内容指纹，同时用作文件名（天然去重）
     * @param ext      扩展名，可为空
     * @return 落盘的相对路径（存进 {@code ai_knowledge_doc.file_path}）
     */
    public String store(String sourceId, String ext, byte[] bytes) throws IOException {
        String name = (ext == null || ext.isBlank()) ? sourceId : sourceId + "." + ext;
        Files.write(root.resolve(name), bytes);
        return name;
    }

    /** 把相对路径解析成绝对路径。跳出落盘目录的一律拒绝。 */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("文件路径越界：" + relativePath);
        }
        return resolved;
    }

    /**
     * Office 文档 → PDF。
     *
     * <p><b>失败一律返回 null，绝不抛异常。</b>预览是锦上添花，入库是本职——
     * 项目里已经吃过一次亏：一个非致命的步骤把整条主流程拖垮
     * （踩坑第 35 条，Office 文档被误判"需要 OCR"之后整篇解析失败）。
     * 调用方拿到 null 就当"这份没有预览"，角标降级成只展开文字片段。
     *
     * @return 预览件的相对路径；转换失败返回 null
     */
    public String convertOfficeToPdf(String sourceId, String relativePath) {
        Path source = resolve(relativePath);
        Path tmpDir = root.resolve("convert-tmp");

        try {
            Files.createDirectories(tmpDir);
            // soffice 的输出文件名是"输入文件的 basename + .pdf"，所以先转到临时目录，
            // 再改名成 <sourceId>.preview.pdf——不然会和原件撞名。
            Process process = new ProcessBuilder(
                    "soffice", "--headless", "--convert-to", "pdf",
                    "--outdir", tmpDir.toString(), source.toString())
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("转 PDF 超时（{} 秒）：{}", CONVERT_TIMEOUT_SECONDS, relativePath);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("转 PDF 失败（退出码 {}）：{} / {}", process.exitValue(), relativePath, output);
                return null;
            }

            String base = source.getFileName().toString();
            int dot = base.lastIndexOf('.');
            Path produced = tmpDir.resolve((dot > 0 ? base.substring(0, dot) : base) + ".pdf");
            if (!Files.exists(produced)) {
                log.warn("转 PDF 没有产出文件：{} / {}", relativePath, output);
                return null;
            }

            String previewName = sourceId + ".preview.pdf";
            Files.move(produced, root.resolve(previewName), StandardCopyOption.REPLACE_EXISTING);
            return previewName;

        } catch (IOException e) {
            // 最常见的原因是 PATH 里没有 soffice（LibreOffice 没装，或应用是在改 PATH 之前
            // 开的终端里启动的——这个坑项目里踩过）。日志要说清是"没装"而不是"文件坏了"。
            log.warn("转 PDF 失败（{}）：{}", e.getMessage(), relativePath);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

package com.jixiejia.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.jixiejia.agent.rag.parse.DocumentParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    /** 扩展名白名单：只认字母数字。见 {@link #safeExtension} 的说明。 */
    private static final Pattern SAFE_EXT = Pattern.compile("[a-z0-9]{1,10}");

    /** 转 PDF 的超时。LibreOffice 冷启动要几秒，大文件更久 */
    private static final long CONVERT_TIMEOUT_SECONDS = 120;

    private final Path root;
    private final String sofficeCommand;

    @Autowired
    public KnowledgeFileStore(@Value("${rag.upload.dir:data/knowledge-files}") String dir,
                              @Value("${rag.upload.soffice:soffice}") String sofficeCommand) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.sofficeCommand = sofficeCommand;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 落盘目录建不出来是启动期就该炸的问题：越晚发现，丢的原始文件越多
            throw new IllegalStateException("无法创建知识库文件目录：" + root, e);
        }
        log.info("知识库原件目录：{}，转 PDF 命令：{}", root, sofficeCommand);
    }

    /** 测试用：只指定目录，转换命令用默认值。 */
    public KnowledgeFileStore(String dir) {
        this(dir, "soffice");
    }

    /** 落盘根目录。给测试和排查用（Task 6 的测试会直接取它构造路径）。 */
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
     * 这份文档该交给浏览器展示的文件（相对路径）。
     *
     * <p><b>返回 null 就表示"没有可预览的形式"。</b>刻意返回路径而不是布尔值：
     * "能不能预览"和"该取哪个文件"本来是**同一个决策**——拆成两半，
     * 调用方就得把"Office 取预览件、其余取原件"这条分支再写一遍，
     * 两处判据迟早会漂移（实测已经漂过一次：一处判 `!= null`、另一处判 `isBlank()`，
     * 于是 `previewPath` 为空串时一边说能预览、另一边返回 404）。
     *
     * <p>放在这里而不是答案服务里：它讲的是"原件/预览件的形态"，
     * 和 {@link #kindOf}、{@link #convertOfficeToPdf} 是同一件事。
     */
    public static String previewFileOf(String filePath, String previewPath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        return switch (kindOf(DocumentParser.extensionOf(filePath))) {
            case PDF, IMAGE, TEXT -> filePath;
            case OFFICE -> (previewPath == null || previewPath.isBlank()) ? null : previewPath;
            case UNSUPPORTED -> null;
        };
    }

    /**
     * 把原件落盘。
     *
     * <p>⚠️ 它保证的是"<b>同一份内容</b>不会堆第二份"，不是"不会有任何堆积"——
     * 内容不同的文件（比如连着传了好几份解析失败的坏文件）各自是独立文件。
     * 调用方在驳回上传时要自己把落下来的文件清掉（见 AdminKnowledgeController.upload）。
     *
     * @param sourceId 内容指纹，同时用作文件名（天然去重）
     * @param ext      扩展名，可为空；含非字母数字会被当成"没有扩展名"（见 {@link #safeExtension}）
     * @return 落盘的相对路径（存进 {@code ai_knowledge_doc.file_path}）
     */
    public String store(String sourceId, String ext, byte[] bytes) throws IOException {
        String safeExt = safeExtension(ext);
        String name = safeExt.isEmpty() ? sourceId : sourceId + "." + safeExt;
        // 再走一遍 resolve()：白名单是第一道，越界校验是第二道，两道都要有
        Files.write(resolve(name), bytes);
        return name;
    }

    /**
     * 扩展名取自上传的**原始文件名**，是用户可控的。
     *
     * <p>⚠️ {@code DocumentParser.extensionOf()} 返回的是最后一个点**之后的全部内容**——
     * 文件名写成 {@code a.xyz/../../foo} 时，它会把 {@code xyz/../../foo} 原样交出来，
     * 直接拼进路径就能爬出落盘目录。所以这里只放行字母数字，
     * 认不出来就当"没有扩展名"（宁可这份原件没有扩展名，也不能让它带着路径）。
     */
    private static String safeExtension(String ext) {
        if (ext == null) {
            return "";
        }
        String e = ext.trim().toLowerCase();
        return SAFE_EXT.matcher(e).matches() ? e : "";
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
     * 删掉一份落盘的原件。<b>失败只记日志，绝不抛异常</b>——
     * 它只在"这次上传已经被驳回"的清理路径上调用，清理失败也不该改变对被驳回这件事的结论。
     */
    public void deleteQuietly(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (Exception e) {
            log.warn("清理原件失败（{}）：{}", relativePath, e.getMessage());
        }
    }

    /**
     * Office 文档 → PDF。
     *
     * <p><b>操作失败一律返回 null，绝不抛异常。</b>预览是锦上添花，入库是本职——
     * 项目里已经吃过一次亏：一个非致命的步骤把整条主流程拖垮
     * （踩坑第 35 条，Office 文档被误判"需要 OCR"之后整篇解析失败）。
     * 调用方拿到 null 就当"这份没有预览"，角标降级成只展开文字片段。
     *
     * <p>唯一例外是<b>相对路径越界</b>——那是非法输入/攻击信号，不该被静默吞掉，
     * 会抛 {@link IllegalArgumentException}，和"转换没成功"区别对待。
     *
     * @return 预览件的相对路径；转换失败返回 null
     */
    public String convertOfficeToPdf(String sourceId, String relativePath) {
        // resolve 在 try 外面：越界是非法输入，抛异常；try 里的"转换失败"才降级成 null
        Path source = resolve(relativePath);
        Path tmpDir = null;

        try {
            // 每次转换用独立临时目录：并发转换互不干扰，也方便失败后整目录清掉
            tmpDir = Files.createTempDirectory(root, "convert-");

            Process process = new ProcessBuilder(
                    sofficeCommand, "--headless", "--convert-to", "pdf",
                    "--outdir", tmpDir.toString(), source.toString())
                    .redirectErrorStream(true)
                    .start();

            // ⚠️ 读输出必须在**独立线程**里做：readAllBytes 会一直阻塞到 stdout 关闭，
            // 进程卡死而管道没关时主线程永远走不到下面的 waitFor(超时)——那样超时就成了摆设。
            CompletableFuture<String> reader = CompletableFuture.supplyAsync(() -> {
                try (var in = process.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);   // 等它真的死掉，回收文件句柄
                log.warn("转 PDF 超时（{} 秒）：{}", CONVERT_TIMEOUT_SECONDS, relativePath);
                return null;
            }
            String output = reader.getNow("");

            // soffice 的输出文件名是"输入文件的 basename + .pdf"，所以先转到临时目录，
            // 再改名成 <sourceId>.preview.pdf——不然会和原件撞名。
            String base = source.getFileName().toString();
            int dot = base.lastIndexOf('.');
            Path produced = tmpDir.resolve((dot > 0 ? base.substring(0, dot) : base) + ".pdf");

            // ⚠️ 先判产物在不在，退出码只作线索——踩坑第 30 条：
            // 外部进程的退出码不是可靠的成功信号（lit 输出正确结果却返回 1）。
            if (!Files.exists(produced)) {
                log.warn("转 PDF 没有产出文件（退出码 {}）：{} / {}", process.exitValue(), relativePath, output);
                return null;
            }
            if (process.exitValue() != 0) {
                // 退出码非 0 但有产物：以产物为准，退出码只作排查线索
                log.info("转 PDF 退出码非 0 但产物已生成，照常使用（{}）：{}", process.exitValue(), relativePath);
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
        } finally {
            // 清理这次转换的临时目录：成功时产物已被 move 走，失败时可能留半成品。
            deleteQuietly(tmpDir);
        }
    }

    /** 递归删除临时目录，失败不抛——残留一个空目录比拖垮主流程轻得多。 */
    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不掉就算了，垃圾文件不会影响主流程
                }
            });
        } catch (IOException ignored) {
            // 目录都列不出来，更没必要管
        }
    }
}

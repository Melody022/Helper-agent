package com.jixiejia.agent.rag.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调 LiteParse（{@code lit} CLI）解析文档。
 *
 * <p>为什么是"调外部进程"而不是引一个 Java 库：LiteParse 是 LlamaIndex 用 Rust 写的，
 * 只有 Rust / Python / Node / WASM 绑定，<b>没有 Java 绑定</b>。它在本地做版面感知的
 * 文本与表格抽取，速度快（实测有文本层的 PDF 约 0.35 秒/页）且不花钱，值得为它开个进程。
 *
 * <p><b>两个必须记住的坑</b>：
 * <ol>
 *   <li><b>一律加 {@code --no-ocr}</b>。它内置的 Tesseract OCR 要去 GitHub 下载语言包
 *       （本机网络到不了），而且默认语言是 {@code eng}，中文根本没法用。
 *       扫描件改走 {@link MultimodalOcrClient}。</li>
 *   <li><b>参数必须用 List 分开传，绝不拼成一整条命令字符串</b>。
 *       文件名是用户可控的，拼字符串就是命令注入。</li>
 * </ol>
 */
@Slf4j
@Component
public class LiteParseClient {

    /**
     * markdown 输出里用来分隔页面的标记。
     *
     * <p>实测：31 页的 PDF 恰好输出 30 个这样的分隔行。有了它就能一次调用拿到
     * "带页码边界的 markdown"，不必一页开一个进程——跨页表格合并正是靠它。
     *
     * <p>注意文档正文里本来就可能出现短横线（表格分隔行是 {@code |---|}，
     * 水平线是 {@code ---}），所以这里**只认恰好 5 个短横线**，并在使用时用页数交叉校验。
     */
    private static final String PAGE_SEPARATOR = "-----";

    /** 单次调用的超时。解析是同步的，不能让一个坏文件把请求线程挂死。 */
    @Value("${rag.parse.lit-timeout-seconds:180}")
    private int timeoutSeconds;

    /** CLI 命令名。装了 npm 全局包的机器上通常是 lit，Windows 下实际是 lit.cmd。 */
    @Value("${rag.parse.lit-command:lit}")
    private String command;

    /** 启动时探测一次的结果，避免每次调用都先去试一遍。 */
    private volatile Boolean available;

    /** lit 是否可用。不可用时上传功能整体降级（纯文本仍可入库）。 */
    public boolean available() {
        if (available == null) {
            try {
                run(List.of(command, "--version"));
                available = true;
            } catch (Exception e) {
                available = false;
                log.warn("LiteParse（{}）不可用，PDF/Word 解析将不可用：{}", command, e.toString());
            }
        }
        return available;
    }

    /** 每页的复杂度信息，来自 {@code lit is-complex}。 */
    public record PageInfo(int pageNumber, double textCoverage, boolean needsOcr, List<String> reasons) {

        /**
         * 这一页是不是**真的没有文本层**（而不是"有文字但夹了图"）。
         *
         * <p>这是整个分流逻辑里最容易被写错的一处。{@code needsOcr} 这个布尔值
         * 会被"页面里内嵌了图片"点亮——实测一份文本完好（textLength 2988、
         * textCoverage 0.25）的简历 PDF，它的 {@code needsOcr} 也是 true，
         * reason 写的是 {@code embedded-images}。若照这个布尔值分流，会把大量正常文本页
         * 送去多模态 OCR，白白烧钱。
         *
         * <p>所以判据是：文本覆盖率低到几乎没有，或者 reason 明确说文本有问题。
         */
        public boolean needsRealOcr(double coverageThreshold) {
            if (textCoverage < coverageThreshold) {
                return true;
            }
            for (String r : reasons) {
                if ("sparse-text".equals(r) || "garbled".equals(r) || "no-text-layer".equals(r)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 逐页探测。解析失败时抛 {@link DocumentParseException}。 */
    public List<PageInfo> inspect(Path file) {
        List<String> args = List.of(command, "is-complex", file.toString(), "--compact", "-q");
        String out = run(args);
        try {
            return PageInfoParser.parse(out);
        } catch (Exception e) {
            throw new DocumentParseException("解析文档结构失败：" + e.getMessage(), e);
        }
    }

    /**
     * 抽取 markdown 文本。
     *
     * @param pages 为 null 表示整篇；否则只解析这几页
     */
    public String parseMarkdown(Path file, List<Integer> pages) {
        List<String> args = new ArrayList<>(List.of(
                command, "parse", file.toString(),
                "--format", "markdown",
                "--no-ocr",            // 见类注释：内置 OCR 不可用，必须关
                "-q"));
        if (pages != null && !pages.isEmpty()) {
            args.add("--target-pages");
            args.add(joinPages(pages));
        }
        return run(args);
    }

    /**
     * 把指定页渲染成 PNG，交给多模态模型做 OCR。
     *
     * @return 按页序排列的图片路径
     */
    public List<Path> screenshot(Path file, List<Integer> pages, int dpi, Path outDir) {
        List<String> args = new ArrayList<>(List.of(
                command, "screenshot", file.toString(),
                "-o", outDir.toString(),
                "--dpi", String.valueOf(dpi),
                "-q"));
        if (pages != null && !pages.isEmpty()) {
            args.add("--target-pages");
            args.add(joinPages(pages));
        }
        run(args);

        try (var stream = Files.list(outDir)) {
            return stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DocumentParseException("截图输出目录读取失败：" + e.getMessage(), e);
        }
    }

    /** 页面分隔标记，供调用方切页。 */
    public static String pageSeparator() {
        return PAGE_SEPARATOR;
    }

    private static String joinPages(List<Integer> pages) {
        return String.join(",", pages.stream().map(String::valueOf).toList());
    }

    /**
     * 跑一次 CLI 并返回 stdout。
     *
     * <p>失败时把 stderr 里的信息带进异常——lit 的报错是有用的
     * （比如 "LibreOffice is not installed"），直接透给用户比"解析失败"强得多。
     */
    private String run(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // Windows 上 npm 全局包装的是 .cmd，ProcessBuilder 有时找不到，
            // 用 cmd /c 兜一层。参数仍按 List 传，Java 负责转义。
            process = startViaCmd(args, e);
        }

        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new DocumentParseException("文档解析超时（超过 " + timeoutSeconds + " 秒）");
            }
            if (process.exitValue() != 0 && stdout.isBlank()) {
                throw new DocumentParseException(friendlyError(stderr, process.exitValue()));
            }
            if (process.exitValue() != 0) {
                // 实测：lit 会在**输出完整结果的同时**返回非 0 退出码。
                // 最典型的是它内置的 OCR 去 GitHub 下语言包失败——而我们本来就传了 --no-ocr，
                // 那个失败与我们无关。所以判据是"有没有拿到输出"，不是"退出码是不是 0"。
                // 若这里照退出码判失败，所有 PDF 都会被误判成解析失败。
                log.debug("lit 退出码为 {}，但拿到了 {} 字节输出，按成功处理；stderr：{}",
                        process.exitValue(), stdout.length(), stderr.strip());
            }
            return stdout;

        } catch (IOException e) {
            throw new DocumentParseException("读取解析结果失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("文档解析被中断");
        }
    }

    private Process startViaCmd(List<String> args, IOException cause) {
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(args);
        try {
            return new ProcessBuilder(wrapped).start();
        } catch (IOException e) {
            throw new DocumentParseException(
                    "无法调用文档解析工具（" + command + "），请确认已安装 LiteParse 并在 PATH 中。", cause);
        }
    }

    /** 把 lit 的 stderr 翻成一句用户能看懂的话。 */
    private static String friendlyError(String stderr, int exitCode) {
        String s = stderr == null ? "" : stderr;
        if (s.contains("LibreOffice is not installed")) {
            return "解析 Word/Excel 需要先安装 LibreOffice（PDF 与图片不受影响）";
        }
        if (s.contains("not found") || s.contains("is not recognized")) {
            return "文档解析工具不可用，请确认已安装 LiteParse";
        }
        String trimmed = s.strip();
        if (trimmed.isEmpty()) {
            return "文档解析失败（退出码 " + exitCode + "）";
        }
        return "文档解析失败：" + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed);
    }
}

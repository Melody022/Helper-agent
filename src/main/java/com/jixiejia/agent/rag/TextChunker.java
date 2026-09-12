package com.jixiejia.agent.rag;

import com.jixiejia.agent.rag.parse.DocBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 文本切片。切成适合检索和喂给模型的小块。
 *
 * <p>做法是"按句子攒块"，不是按固定字数硬切：
 * <ul>
 *   <li>先按中文句末标点（。！？；换行）断成句子；</li>
 *   <li>再一句句往当前块里塞，塞到接近上限就收尾开新块；</li>
 *   <li>新块开头带上上一块结尾的一小段（重叠），避免"答案正好跨在两块中间"被切断。</li>
 * </ul>
 * 硬切最省事，但会把一句话拦腰截断，检索时两半都匹配不上——
 * 这是知识库答非所问最常见的原因之一。
 */
@Component
public class TextChunker {

    /** 单块目标字符数 */
    private final int maxChars;

    /** 相邻块重叠字符数 */
    private final int overlapChars;

    /** 太短的碎片并进上一块，避免产生一堆没有信息量的块 */
    private static final int MIN_CHARS = 40;

    public TextChunker(@Value("${rag.chunk.max-chars:400}") int maxChars,
                       @Value("${rag.chunk.overlap-chars:60}") int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    /**
     * 一个切好的块。
     *
     * @param tableId     非空表示这是某张表的<b>摘要块</b>；检索命中它之后要按这个 id
     *                    去 {@code ai_knowledge_table} 取回完整表格，不能只把摘要喂给模型
     * @param sectionPath 章节路径，如 {@code "5 安全要求 > 5.4 润滑系统"}；溯源与同章节回填用
     * @param pageNo      所在页（1 起）；溯源展示用
     */
    public record Chunk(int index, String content, String hash, Long tableId,
                        String sectionPath, Integer pageNo) {

        /** 普通正文块，不关联表格、不带结构信息（FAQ 这类短文本用）。 */
        public Chunk(int index, String content, String hash) {
            this(index, content, hash, null, null, null);
        }

        /** 表格摘要块：带 tableId，但没有章节/页码。 */
        public Chunk(int index, String content, String hash, Long tableId) {
            this(index, content, hash, tableId, null, null);
        }
    }

    /**
     * 按带结构信息的段切块，每块继承它所属段的章节路径与页码。
     *
     * <p><b>为什么逐段切而不是先把所有段拼起来再切</b>：拼接会重新丢掉段落边界，
     * 页码和章节归属就传不到块上——那正是我们要解决的问题。
     * 代价是段落之间没有重叠（现在只保证段内相邻块重叠），
     * 对"按页 × 章节"拆出来的段来说可以接受：段边界本身就是天然的语义边界。
     */
    public List<Chunk> chunk(List<DocBlock> blocks) {
        List<Chunk> all = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return all;
        }
        int index = 0;
        for (DocBlock block : blocks) {
            for (Chunk c : chunk(block.text())) {
                all.add(new Chunk(index++, c.content(), c.hash(), null,
                        block.sectionPath(), block.pageNo()));
            }
        }
        return all;
    }

    /** 把正文切成若干块。空白或无有效内容时返回空列表。 */
    public List<Chunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = splitSentences(text);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String sentence : sentences) {
            // 单句就超长（比如没有标点的长表格行），单独成块，不要撑爆整块
            if (sentence.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(build(index++, current.toString()));
                    current.setLength(0);
                }
                for (String piece : hardSplit(sentence, maxChars)) {
                    chunks.add(build(index++, piece));
                }
                continue;
            }

            if (current.length() + sentence.length() > maxChars && current.length() > 0) {
                String finished = current.toString();
                chunks.add(build(index++, finished));
                current.setLength(0);
                // 带上一块结尾的一小段，保证跨块的内容也能被检索到
                String tail = tail(finished, overlapChars);
                if (!tail.isBlank()) {
                    current.append(tail);
                }
            }
            current.append(sentence);
        }

        if (!current.isEmpty()) {
            String last = current.toString().trim();
            // 最后一块太短就并进上一块，别留个孤零零的碎片
            if (last.length() < MIN_CHARS && !chunks.isEmpty()) {
                Chunk previous = chunks.remove(chunks.size() - 1);
                chunks.add(build(previous.index(), previous.content() + last));
            } else if (!last.isBlank()) {
                chunks.add(build(index, last));
            }
        }

        return chunks;
    }

    /** 按中文句末标点和换行断句，标点保留在句尾。 */
    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || c == '!' || c == '?' || c == ';' || c == '.') {
                String s = sb.toString().trim();
                if (!s.isBlank()) {
                    sentences.add(s);
                }
                sb.setLength(0);
            }
        }
        String rest = sb.toString().trim();
        if (!rest.isBlank()) {
            sentences.add(rest);
        }
        return sentences;
    }

    private static List<String> hardSplit(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            pieces.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return pieces;
    }

    private static String tail(String text, int length) {
        return text.length() <= length ? text : text.substring(text.length() - length);
    }

    private static Chunk build(int index, String content) {
        String trimmed = content.trim();
        return new Chunk(index, trimmed, sha256(trimmed));
    }

    /** 内容指纹，用于入库查重与"内容没变就跳过"的判断。 */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 内容指纹（字节版）：对上传的原始文件判重时用，不必先转成字符串。 */
    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算内容指纹失败", e);
        }
    }
}

package com.jixiejia.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切片器的单元测试。不依赖 Spring 与外部服务，纯算。
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(100, 20);

    @Test
    @DisplayName("按句子攒块，不把一句话拦腰截断")
    void splitsOnSentenceBoundaries() {
        String text = "第一句话在这里。第二句话也在这里。第三句话同样在这里。第四句话还是在这里。";

        List<TextChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).isNotEmpty();
        // 每一块都应当以句末标点收尾，说明没有从句中断开
        for (TextChunker.Chunk chunk : chunks) {
            assertThat(chunk.content()).endsWith("。");
        }
        // 拼起来要能覆盖原文的全部内容（重叠部分会重复，但不该丢字）
        String joined = String.join("", chunks.stream().map(TextChunker.Chunk::content).toList());
        assertThat(joined).contains("第一句话").contains("第四句话");
    }

    @Test
    @DisplayName("相邻块之间有重叠，避免答案正好跨在两块中间被切断")
    void adjacentChunksOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("句用来撑长的测试文本内容。");
        }

        List<TextChunker.Chunk> chunks = chunker.chunk(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);

        // 上一块的结尾应当出现在下一块的开头
        for (int i = 0; i < chunks.size() - 1; i++) {
            String tail = chunks.get(i).content();
            String head = chunks.get(i + 1).content();
            String lastFew = tail.substring(Math.max(0, tail.length() - 8));
            assertThat(head).as("第 %d 块与第 %d 块应当有重叠", i, i + 1).contains(lastFew);
        }
    }

    @Test
    @DisplayName("超长无标点的句子单独成块，不撑爆整块")
    void oversizedSentenceIsSplit() {
        String longSentence = "啊".repeat(250) + "。";

        List<TextChunker.Chunk> chunks = chunker.chunk(longSentence);

        assertThat(chunks.size()).isGreaterThan(1);
        for (TextChunker.Chunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("每个块都带内容指纹，用于入库查重")
    void chunksCarryContentHash() {
        List<TextChunker.Chunk> chunks = chunker.chunk("一句话。另一句话。");

        assertThat(chunks).isNotEmpty();
        for (TextChunker.Chunk chunk : chunks) {
            assertThat(chunk.hash()).hasSize(64);
        }
        // 相同内容指纹相同
        assertThat(TextChunker.sha256("abc")).isEqualTo(TextChunker.sha256("abc"));
        assertThat(TextChunker.sha256("abc")).isNotEqualTo(TextChunker.sha256("abd"));
    }

    @Test
    @DisplayName("空文本不产生块")
    void blankTextProducesNothing() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
    }
}

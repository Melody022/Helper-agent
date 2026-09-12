package com.jixiejia.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原件落盘与格式分类。不依赖 Spring：直接 new，指定临时目录。
 */
class KnowledgeFileStoreTest {

    private static final byte[] CONTENT = "这是一份测试原件".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("落盘用内容指纹当文件名，同一份内容重复上传不会堆第二份")
    void storeIsIdempotentBySourceId(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());

        String first = store.store("upload-abc123", "pdf", CONTENT);
        String second = store.store("upload-abc123", "pdf", CONTENT);

        assertThat(first).isEqualTo("upload-abc123.pdf");
        assertThat(second).isEqualTo(first);
        assertThat(dir.toFile().listFiles()).hasSize(1);
    }

    @Test
    @DisplayName("没有扩展名也能落盘")
    void storesWithoutExtension(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());
        assertThat(store.store("upload-noext", "", CONTENT)).isEqualTo("upload-noext");
    }

    @Test
    @DisplayName("格式分类：浏览器渲染不了的是 OFFICE，不是不支持")
    void classifiesKinds() {
        assertThat(KnowledgeFileStore.kindOf("pdf")).isEqualTo(KnowledgeFileStore.PreviewKind.PDF);
        assertThat(KnowledgeFileStore.kindOf("png")).isEqualTo(KnowledgeFileStore.PreviewKind.IMAGE);
        assertThat(KnowledgeFileStore.kindOf("jpg")).isEqualTo(KnowledgeFileStore.PreviewKind.IMAGE);
        assertThat(KnowledgeFileStore.kindOf("txt")).isEqualTo(KnowledgeFileStore.PreviewKind.TEXT);
        assertThat(KnowledgeFileStore.kindOf("csv")).isEqualTo(KnowledgeFileStore.PreviewKind.TEXT);
        assertThat(KnowledgeFileStore.kindOf("md")).isEqualTo(KnowledgeFileStore.PreviewKind.TEXT);
        assertThat(KnowledgeFileStore.kindOf("docx")).isEqualTo(KnowledgeFileStore.PreviewKind.OFFICE);
        assertThat(KnowledgeFileStore.kindOf("xlsx")).isEqualTo(KnowledgeFileStore.PreviewKind.OFFICE);
        assertThat(KnowledgeFileStore.kindOf("pptx")).isEqualTo(KnowledgeFileStore.PreviewKind.OFFICE);
        assertThat(KnowledgeFileStore.kindOf("zip")).isEqualTo(KnowledgeFileStore.PreviewKind.UNSUPPORTED);
        assertThat(KnowledgeFileStore.kindOf(null)).isEqualTo(KnowledgeFileStore.PreviewKind.UNSUPPORTED);
    }

    @Test
    @DisplayName("resolve 不允许跳出落盘目录")
    void resolveRejectsPathTraversal(@TempDir Path dir) {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());
        assertThatThrownBy(() -> store.resolve("../../windows/win.ini"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("resolve 能取回落盘的文件")
    void resolveFindsStoredFile(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());
        String name = store.store("upload-xyz", "txt", CONTENT);

        Path path = store.resolve(name);

        assertThat(path).exists();
        assertThat(Files.readString(path)).isEqualTo("这是一份测试原件");
    }
}

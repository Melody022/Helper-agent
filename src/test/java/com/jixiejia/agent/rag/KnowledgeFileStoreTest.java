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
    @DisplayName("该给浏览器看哪个文件：PDF/图片/纯文本就是原件本身")
    void previewFileOfReturnsOriginalForRenderableKinds() {
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.pdf", null)).isEqualTo("upload-a.pdf");
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.png", null)).isEqualTo("upload-a.png");
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.txt", null)).isEqualTo("upload-a.txt");
    }

    @Test
    @DisplayName("Office 文档取转出来的预览件；没转出来就没有可预览的形式")
    void previewFileOfUsesConvertedPdfForOffice() {
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.docx", "upload-a.preview.pdf"))
                .isEqualTo("upload-a.preview.pdf");
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.docx", null)).isNull();
        // 空串也要当成"没转出来"——两处判据不一致过一次，就是这里漏的
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.docx", "")).isNull();
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.docx", "   ")).isNull();
    }

    @Test
    @DisplayName("没有原件、或格式不支持预览的，一律返回 null")
    void previewFileOfReturnsNullWhenNothingToShow() {
        assertThat(KnowledgeFileStore.previewFileOf(null, null)).isNull();
        assertThat(KnowledgeFileStore.previewFileOf("", null)).isNull();
        assertThat(KnowledgeFileStore.previewFileOf("   ", null)).isNull();
        assertThat(KnowledgeFileStore.previewFileOf("upload-a.zip", null)).isNull();
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

    @Test
    @DisplayName("扩展名里带路径分隔符时按'没有扩展名'处理，绝不爬出落盘目录")
    void storeIgnoresPathTraversalInExtension(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());

        // DocumentParser.extensionOf("a.xyz/../../foo") 返回的就是 "xyz/../../foo"
        String name = store.store("upload-safe", "xyz/../../evil", CONTENT);

        assertThat(name).isEqualTo("upload-safe");
        assertThat(store.resolve(name)).exists();
        // 上一层目录里绝不能多出东西
        assertThat(dir.getParent().resolve("evil")).doesNotExist();
    }

    @Test
    @DisplayName("转换命令不存在时返回 null，不抛异常——预览失败不能拖垮入库")
    void convertFailsQuietlyWhenCommandMissing(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString(), "no-such-command-xyz-123");
        String stored = store.store("upload-doc", "docx", CONTENT);

        assertThat(store.convertOfficeToPdf("upload-doc", stored)).isNull();
    }

    @Test
    @DisplayName("相对路径越界是非法输入，会抛 IllegalArgumentException（和'操作失败'区别对待）")
    void convertRejectsTraversalPath(@TempDir Path dir) {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());

        assertThatThrownBy(() -> store.convertOfficeToPdf("upload-x", "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deleteQuietly 删得掉已落盘的文件，删不存在的也不抛异常")
    void deleteQuietlyRemovesStoredFile(@TempDir Path dir) throws Exception {
        KnowledgeFileStore store = new KnowledgeFileStore(dir.toString());
        String name = store.store("upload-del", "txt", CONTENT);
        assertThat(store.resolve(name)).exists();

        store.deleteQuietly(name);
        assertThat(store.resolve(name)).doesNotExist();

        // 再删一次、传 null、传空串：都只是记日志，不抛
        store.deleteQuietly(name);
        store.deleteQuietly(null);
        store.deleteQuietly("");
    }
}

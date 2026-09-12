package com.jixiejia.agent.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.auth.AuthService;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.auth.TokenStore;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.entity.ai.AiRole;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.entity.ai.AiUserRole;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserRoleMapper;
import com.jixiejia.agent.rag.KnowledgeFileStore;
import com.jixiejia.agent.rag.KnowledgeIndex;
import com.jixiejia.agent.rag.KnowledgeIngestionService;
import com.jixiejia.agent.rag.TextChunker;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传原件 → 落盘 → 预览接口的端到端验证，外加权限边界。
 *
 * <p>用纯文本（.txt）而不是 PDF 来测：走 PLAIN_TEXT 分支，不调 LiteParse、
 * 不调 OCR、不花一分钱，几十毫秒跑完。**测的是"文件有没有落下来、
 * 能不能按原件类型取回去、谁能取"，不是解析质量**——解析质量归 DocumentUploadTest 管。
 *
 * <p>权限那两条是这个类存在的主要理由：聊天页的普通用户要能预览、但拿不到下载，
 * 而"分角色"是最容易嘴上说说、实际漏掉的地方（AuthTest 里也是同样的理由用
 * 真实 HTTP 打接口而不是只测 Service）。
 */
@SpringBootTest
class KnowledgeFileApiTest {

    private static final String PASSWORD = "Test#123456";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private AuthService authService;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private AiKnowledgeDocMapper docMapper;
    @Autowired
    private AiUserMapper userMapper;
    @Autowired
    private AiUserRoleMapper userRoleMapper;
    @Autowired
    private AiRoleMapper roleMapper;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private KnowledgeFileStore fileStore;
    @Autowired
    private KnowledgeIngestionService ingestionService;
    @Autowired
    private KnowledgeIndex knowledgeIndex;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private String adminToken;
    private String userToken;
    private Long adminUserId;
    private Long normalUserId;

    /** 本次用例产生的文档，收尾时逐个清掉 */
    private final List<Long> createdDocIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Spring Boot 4.1 没有随包提供 @AutoConfigureMockMvc，从上下文手工构建。
        // 效果等价：DispatcherServlet 的拦截器照样执行，鉴权能被真实验证到（同 AuthTest）。
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // 入库要写 ES，没有 ES 就没法测；跳过而不是失败（同 M6KnowledgeTest 的做法）
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        // 自己建测试账号，不依赖开发库里的 admin——那样会被别人改过的密码搞挂
        adminUserId = createUser(CurrentUser.ROLE_ADMIN);
        normalUserId = createUser(CurrentUser.ROLE_USER);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Long id : createdDocIds) {
            // 复用线上的删除逻辑：ES 切片、MySQL 切片与表格一起清。
            // 漏了会留下查得到但已失效的旧内容，挤掉后续检索的正确答案（踩坑第 26 条）
            ingestionService.deleteDocument(id);
            // deleteDocument 最后一步是逻辑删，行还在表里，补一次物理删
            jdbcTemplate.update("DELETE FROM ai_knowledge_doc WHERE id = ?", id);
        }
        createdDocIds.clear();

        for (Long id : new Long[]{adminUserId, normalUserId}) {
            if (id == null) {
                continue;
            }
            tokenStore.revokeByUserId(id);
            userRoleMapper.delete(Wrappers.<AiUserRole>lambdaQuery().eq(AiUserRole::getUserId, id));
            userMapper.deleteById(id);   // 走 @TableLogic 逻辑删
        }
        // 上面那句只把 del_flag 置成 '2'，行还留着，越积越多——补一次物理删
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
    }

    @Test
    @DisplayName("上传纯文本：原件落盘、文档记录带上文件信息")
    void uploadStoresOriginal() throws Exception {
        byte[] bytes = text("落盘");

        Long id = upload("费率说明.txt", bytes);

        AiKnowledgeDoc doc = docMapper.selectById(id);
        assertThat(doc.getFileName()).isEqualTo("费率说明.txt");
        assertThat(doc.getFilePath()).isEqualTo(sourceIdOf(bytes) + ".txt");
        assertThat(doc.getFileSize()).isEqualTo(bytes.length);
        // 纯文本原件浏览器自己能显示，**不该**另存一份预览件
        assertThat(doc.getPreviewPath()).isNull();

        Path onDisk = fileStore.resolve(doc.getFilePath());
        assertThat(onDisk).exists();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("传两份内容相同、文件名不同的文件，磁盘上只有一个")
    void identicalContentStoredOnce() throws Exception {
        byte[] bytes = text("去重");

        upload("a.txt", bytes);
        upload("b.txt", bytes);

        String sourceId = sourceIdOf(bytes);
        assertThat(fileStore.resolve(sourceId + ".txt")).exists();

        // 以这个指纹开头的文件只该有一个（convert-tmp 是目录，已被 isRegularFile 过滤）
        try (var files = Files.list(fileStore.root())) {
            assertThat(files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(sourceId)))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("预览接口：未登录 401，登录用户能按原件类型取回")
    void previewRespectsAuth() throws Exception {
        byte[] bytes = text("预览");
        Long id = upload("费率说明.txt", bytes);

        mockMvc.perform(get("/api/knowledge/files/" + id + "/preview"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/knowledge/files/" + id + "/preview")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                // 原件是纯文本，就该按 text/plain 给回去（不是一律 application/pdf）
                .andExpect(MockMvcResultMatchers.header().string("Content-Type",
                        Matchers.startsWith("text/plain")))
                // inline：只预览不给另存
                .andExpect(MockMvcResultMatchers.header().string("Content-Disposition", "inline"))
                .andExpect(MockMvcResultMatchers.content().string(
                        new String(bytes, StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("下载接口：普通用户拿不到，ADMIN 能拿到原始文件")
    void downloadIsAdminOnly() throws Exception {
        Long id = upload("费率说明.txt", text("下载"));

        mockMvc.perform(get("/api/admin/knowledge/docs/" + id + "/file")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/knowledge/docs/" + id + "/file")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.header().string("Content-Disposition",
                        Matchers.containsString("attachment")))
                // 下载给的是**原始文件**，不是预览件——取回来的字节该和上传的一模一样。
                // （不校验文件名：Content-Disposition 里的中文名是 RFC 5987 百分号编码的，
                //   写 containsString("费率说明.txt") 会假失败）
                .andExpect(MockMvcResultMatchers.content().bytes(text("下载")));
    }

    @Test
    @DisplayName("删文档要把磁盘上的原件一起删掉")
    void deleteDocumentRemovesOriginalFile() throws Exception {
        byte[] bytes = text("删除");
        Long id = upload("费率说明.txt", bytes);

        String filePath = docMapper.selectById(id).getFilePath();
        Path onDisk = fileStore.resolve(filePath);
        assertThat(onDisk).exists();

        mockMvc.perform(delete("/api/admin/knowledge/docs/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 不删的话它会**永远**留在磁盘上——这个目录没有别的清理机制，
        // 而"删除文档"在用户看来就是"这东西没了"。
        assertThat(onDisk).doesNotExist();
        createdDocIds.remove(id);   // 已经删过了，tearDown 不用再管
    }

    @Test
    @DisplayName("平台文章类资料没有原件：预览接口 404，不是 500")
    void previewWithoutOriginalIsNotFound() throws Exception {
        // 造一条非上传来源的文档（模拟文章入库），它没有 file_path
        AiKnowledgeDoc doc = new AiKnowledgeDoc();
        doc.setDocType("article");
        doc.setSourceId("test-article-" + UUID.randomUUID());
        doc.setTitle("测试文章");
        doc.setContent("正文");
        doc.setContentHash(TextChunker.sha256("正文"));
        doc.setStatus("INDEXED");
        doc.setDelFlag("0");
        docMapper.insert(doc);
        createdDocIds.add(doc.getId());

        mockMvc.perform(get("/api/knowledge/files/" + doc.getId() + "/preview")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    /** 建一个测试账号并登录，返回其 id。 */
    private Long createUser(String roleKey) {
        String username = "test_" + roleKey.toLowerCase() + "_" + UUID.randomUUID();
        AiUser user = new AiUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setStatus("0");
        user.setDelFlag("0");
        userMapper.insert(user);

        AiRole role = roleMapper.selectOne(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getRoleKey, roleKey));
        assertThat(role).as("ai_role 里应当有 " + roleKey).isNotNull();

        AiUserRole link = new AiUserRole();
        link.setUserId(user.getId());
        link.setRoleId(role.getId());
        userRoleMapper.insert(link);

        String token = authService.login(username, PASSWORD).orElseThrow();
        if (CurrentUser.ROLE_ADMIN.equals(roleKey)) {
            adminToken = token;
        } else {
            userToken = token;
        }
        return user.getId();
    }

    /** 上传一个纯文本文件，返回文档 id；产生的文档记下来供 tearDown 清理。 */
    private Long upload(String filename, byte[] bytes) throws Exception {
        mockMvc.perform(multipart("/api/admin/knowledge/upload")
                        .file(new MockMultipartFile("file", filename, "text/plain", bytes))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String sourceId = sourceIdOf(bytes);
        AiKnowledgeDoc doc = docMapper.selectOne(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                .eq(AiKnowledgeDoc::getDocType, "upload")
                .eq(AiKnowledgeDoc::getSourceId, sourceId));
        assertThat(doc).as("上传后应当查得到文档记录").isNotNull();

        if (!createdDocIds.contains(doc.getId())) {
            createdDocIds.add(doc.getId());
        }
        return doc.getId();
    }

    private static String sourceIdOf(byte[] bytes) {
        return "upload-" + TextChunker.sha256(bytes).substring(0, 16);
    }

    /** 每个用例用不同的内容，避免指纹撞车互相干扰。 */
    private static byte[] text(String marker) {
        return ("平台服务费按成交额 3% 收取。" + marker).getBytes(StandardCharsets.UTF_8);
    }
}

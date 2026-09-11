package com.jixiejia.agent.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 启动后把内置的平台规则语料灌进知识库。
 *
 * <p>为什么要自动做：知识库空着的时候，"问规则"这条链路的表现是
 * <b>永远被证据闸拦下、永远转人工</b>——功能看起来是坏的，其实只是没内容。
 * 让它开机自动灌一遍，系统开箱就有规则可查。
 *
 * <p>代价可控：入库按内容指纹跳过没变的内容，第二次启动只做哈希比对，
 * 不重复调 embedding。改了语料文件才会重建对应条目。
 *
 * <p>放异步线程池里做，不阻塞应用启动；失败也不影响其它功能——
 * 知识库不可用只是"问规则"这条道降级，其余照常。
 */
@Slf4j
@Component
public class KnowledgeBootstrap {

    private final KnowledgeIngestionService ingestionService;
    private final AiKnowledgeDocMapper docMapper;
    private final KnowledgeIndex knowledgeIndex;
    private final ThreadPoolTaskExecutor chatExecutor;

    public KnowledgeBootstrap(KnowledgeIngestionService ingestionService,
                              AiKnowledgeDocMapper docMapper,
                              KnowledgeIndex knowledgeIndex,
                              @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor) {
        this.ingestionService = ingestionService;
        this.docMapper = docMapper;
        this.knowledgeIndex = knowledgeIndex;
        this.chatExecutor = chatExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!knowledgeIndex.available()) {
            log.info("Elasticsearch 不可用，跳过内置规则入库；知识库相关问题会转人工");
            return;
        }
        chatExecutor.execute(() -> {
            try {
                Long existing = docMapper.selectCount(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                        .eq(AiKnowledgeDoc::getDocType, "faq")
                        .eq(AiKnowledgeDoc::getStatus, "INDEXED"));
                if (existing != null && existing > 0) {
                    log.debug("知识库已有 {} 条规则，跳过首次入库", existing);
                    return;
                }
                KnowledgeIngestionService.IngestionReport report = ingestionService.ingestBuiltinFaq();
                log.info("内置规则首次入库：新增 {} 条，共 {} 个切片",
                        report.ingested(), report.chunks());
            } catch (Exception e) {
                log.warn("内置规则入库失败（不影响其它功能）：{}", e.toString());
            }
        });
    }
}

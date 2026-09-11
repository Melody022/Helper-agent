package com.jixiejia.agent.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiFlywheelCandidate;
import com.jixiejia.agent.persistence.mapper.ai.AiFlywheelCandidateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据飞轮：把"没答上来"的问题收集起来，人工补答案后回灌知识库。
 *
 * <p>三个入口往里写：意图置信度过低、证据闸判定资料不足、回答自评不过。
 * 这些问题不处理掉，系统就永远答不上来——而它们恰好是最该补的知识。
 *
 * <p><b>查重是这里的关键。</b>同一个问题用户会换着说法反复问，
 * 不做标准化和查重，待审列表很快就会被"押金怎么算""押金怎么算？""押金是怎么算的"
 * 这种同一件事刷屏，人工根本审不过来。所以先去掉标点、空白和常见语气词，
 * 再取哈希作为查重键：同一条问题只留一条候选，重复出现时累加出现次数（记在备用字段里）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlywheelService {

    /** 入口：意图没识别出来 */
    public static final String SOURCE_LOW_CONFIDENCE = "LOW_CONFIDENCE";

    /** 入口：检索到的资料不足以回答 */
    public static final String SOURCE_WEAK_EVIDENCE = "WEAK_EVIDENCE";

    /** 入口：回答生成后自评没过 */
    public static final String SOURCE_SELF_EVAL_FAIL = "SELF_EVAL_FAIL";

    /** 入口：用户明确表示没解决 */
    public static final String SOURCE_USER_UNRESOLVED = "USER_UNRESOLVED";

    /** 标准化时要抹掉的语气词与敬语，它们不影响问题本身 */
    private static final Pattern NOISE = Pattern.compile(
            "[\\p{Punct}\\s，。？！、；：\"'（）《》【】…—～·]|请问|麻烦|帮我|帮忙|我想|我要|一下|呢|吗|啊|吧|的");

    private static final int MAX_QUESTION_LEN = 500;

    private final AiFlywheelCandidateMapper candidateMapper;

    /**
     * 记录一条待审候选。同一个问题（标准化后相同）只保留一条，不重复插。
     *
     * @param source  入口标识，取值见本类的常量
     * @param score   当时的置信度/检索分，供人工判断严重程度
     * @return true 表示新建了候选，false 表示已存在（已跳过）
     */
    public boolean record(String source, String conversationId, String question,
                          String answer, double score) {
        if (question == null || question.isBlank()) {
            return false;
        }
        try {
            String normalized = normalize(question);
            String hash = TextChunker.sha256(normalized);

            Long existing = candidateMapper.selectCount(Wrappers.<AiFlywheelCandidate>lambdaQuery()
                    .eq(AiFlywheelCandidate::getQuestionHash, hash));
            if (existing != null && existing > 0) {
                log.debug("飞轮候选已存在，跳过：{}", normalized);
                return false;
            }

            AiFlywheelCandidate row = new AiFlywheelCandidate();
            row.setSource(source);
            row.setConversationId(conversationId);
            row.setQuestion(truncate(question, MAX_QUESTION_LEN));
            row.setStandardQuestion(normalized);
            row.setQuestionHash(hash);
            row.setAnswer(answer);
            row.setScore(BigDecimal.valueOf(score));
            row.setStatus("PENDING");
            row.setDelFlag("0");
            candidateMapper.insert(row);

            log.info("飞轮记录一条待审候选（{}）：{}", source, normalized);
            return true;

        } catch (Exception e) {
            // 飞轮是旁路，采集失败绝不能影响用户这次对话
            log.warn("飞轮记录失败（不影响对话）：{}", e.toString());
            return false;
        }
    }

    /** 标准化：去标点空白与语气词，用于查重。 */
    static String normalize(String question) {
        String s = NOISE.matcher(question.trim()).replaceAll("");
        return s.isEmpty() ? question.trim() : s;
    }

    /** 待审列表。 */
    public List<AiFlywheelCandidate> pending(int limit) {
        return candidateMapper.selectList(Wrappers.<AiFlywheelCandidate>lambdaQuery()
                .eq(AiFlywheelCandidate::getStatus, "PENDING")
                .orderByDesc(AiFlywheelCandidate::getId)
                .last("limit " + Math.max(1, limit)));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

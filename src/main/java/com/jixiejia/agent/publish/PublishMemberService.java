package com.jixiejia.agent.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiPublishMapping;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiPublishMappingMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI 账号 ↔ 平台会员的绑定。
 *
 * <p>发布落库时 {@code customer_id} 必须填平台会员 id，不能填 AI 账号 id——
 * 两张表的 id 各自独立，拿 AI 账号 id 填进去，在平台上会指向一个<b>不相干的会员</b>，
 * 等于把设备挂到了别人名下。所以发布前必须先确认这个映射。
 *
 * <p>映射不存在时尝试自动绑定：用手机号去 ums_member 里找同一个人的账号。
 * 找不到就<b>拒绝发布</b>并给出明确说明，而不是猜一个 id 填进去——
 * 发布是把设备挂到某个人名下，猜错比拒绝严重得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishMemberService {

    private final AiPublishMappingMapper mappingMapper;
    private final AiUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 已绑定的会员 id。 */
    public Optional<Long> resolve(Long aiUserId) {
        if (aiUserId == null) {
            return Optional.empty();
        }
        AiPublishMapping mapping = mappingMapper.selectOne(
                Wrappers.<AiPublishMapping>lambdaQuery().eq(AiPublishMapping::getUserId, aiUserId));
        return mapping == null ? Optional.empty() : Optional.of(mapping.getMemberId());
    }

    /**
     * 解析会员 id，必要时尝试自动绑定。
     *
     * @return 解析不到时返回 empty，调用方应拒绝发布并提示用户
     */
    public Optional<Long> resolveOrAutoLink(Long aiUserId) {
        Optional<Long> existing = resolve(aiUserId);
        if (existing.isPresent()) {
            return existing;
        }
        return autoLinkByMobile(aiUserId);
    }

    /** 手动绑定，供管理台使用。 */
    public void link(Long aiUserId, Long memberId) {
        AiPublishMapping existing = mappingMapper.selectOne(
                Wrappers.<AiPublishMapping>lambdaQuery().eq(AiPublishMapping::getUserId, aiUserId));

        if (existing != null) {
            existing.setMemberId(memberId);
            mappingMapper.updateById(existing);
            return;
        }
        AiPublishMapping mapping = new AiPublishMapping();
        mapping.setUserId(aiUserId);
        mapping.setMemberId(memberId);
        mappingMapper.insert(mapping);
    }

    /**
     * 用手机号自动绑定：AI 账号和平台会员是同一个人时，手机号通常一致。
     *
     * <p>只认唯一匹配。手机号在 ums_member 上是唯一索引，但为保险仍校验条数——
     * 命中多条说明数据有问题，宁可放弃绑定也不能随便挑一个。
     */
    private Optional<Long> autoLinkByMobile(Long aiUserId) {
        AiUser user = userMapper.selectOne(
                Wrappers.<AiUser>lambdaQuery().eq(AiUser::getId, aiUserId));
        if (user == null || user.getMobile() == null || user.getMobile().isBlank()) {
            return Optional.empty();
        }

        List<Long> matched = jdbcTemplate.queryForList(
                "SELECT id FROM ums_member WHERE mobile = ? AND del_flag = '0'",
                Long.class, user.getMobile());

        if (matched.size() != 1) {
            log.info("按手机号自动绑定会员未命中唯一结果，账号 {} 命中 {} 条", aiUserId, matched.size());
            return Optional.empty();
        }

        Long memberId = matched.get(0);
        link(aiUserId, memberId);
        log.info("已按手机号自动绑定：ai_user {} -> ums_member {}", aiUserId, memberId);
        return Optional.of(memberId);
    }
}

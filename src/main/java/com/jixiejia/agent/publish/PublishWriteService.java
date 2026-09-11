package com.jixiejia.agent.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;
import com.jixiejia.agent.persistence.entity.jxj.JxbQiuzu;
import com.jixiejia.agent.persistence.mapper.ai.AiPublishRequestMapper;
import com.jixiejia.agent.persistence.mapper.publish.JxbChuzuWriteMapper;
import com.jixiejia.agent.persistence.mapper.publish.JxbQiuzuWriteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 把确认过的发布请求写进业务表。
 *
 * <p>这是整条链路里<b>唯一</b>真正写业务数据的地方，而且只在用户带着一次性令牌
 * 确认之后才会被执行。落库时的两条硬规矩：
 *
 * <ul>
 *   <li><b>audit_status 一律写 '0'（申请中）</b>，绝不写 '1'（通过）。
 *       AI 代发的信息必须经过平台人工审核才能上线——模型不能替平台做审核决定；</li>
 *   <li><b>customer_id 取平台会员 id</b>，不是 AI 账号 id。取错了设备会挂到别人名下。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishWriteService {

    /** 出租信息落库后的默认状态：待租 */
    private static final String CHUZU_STATUS_AVAILABLE = "dz";

    /** 求租信息落库后的默认状态：进行中 */
    private static final String QIUZU_STATUS_ONGOING = "jxz";

    /** 待审核 */
    private static final String AUDIT_PENDING = "0";

    private final JxbChuzuWriteMapper chuzuWriteMapper;
    private final JxbQiuzuWriteMapper qiuzuWriteMapper;
    private final AiPublishRequestMapper requestMapper;
    private final PublishMemberService memberService;

    /** 落库结果。 */
    public record WriteResult(boolean success, String message, Long bizId) {
    }

    /**
     * 提交发布：写入业务表并把草稿置为已提交。
     *
     * <p>整个过程在一个事务里：业务表写成功、草稿状态没更新，用户会重复提交；
     * 反过来草稿更新了、业务表没写进去，这条发布就永远查不到。
     */
    @Transactional
    public WriteResult submit(AiPublishRequest draft, Map<String, Object> payload) {
        Long memberId = memberService.resolveOrAutoLink(draft.getUserId()).orElse(null);
        if (memberId == null) {
            return new WriteResult(false,
                    "你的账号还没绑定平台会员，没法把信息挂到你的名下。"
                            + "请回复\"转人工\"让客服帮你绑定后再发布。", null);
        }

        PublishTarget target = PublishTarget.byTable(draft.getTargetTable());

        Long bizId;
        try {
            bizId = switch (target) {
                case CHUZU -> insertChuzu(memberId, payload);
                case QIUZU -> insertQiuzu(memberId, payload);
            };
        } catch (Exception e) {
            log.error("发布落库失败，requestNo={}", draft.getRequestNo(), e);
            return new WriteResult(false, "提交的时候出了点问题，请稍后再试或回复\"转人工\"。", null);
        }

        draft.setMemberId(memberId);
        draft.setStatus("SUBMITTED");
        draft.setBizTable(target.table());
        draft.setBizId(bizId);

        // 令牌用完即弃，防止同一条确认被重放提交两次。
        // 这里必须用 lambdaUpdate 显式写 null——MyBatis-Plus 的 updateById
        // 默认跳过 null 字段，用 setConfirmToken(null) + updateById 根本清不掉，
        // 令牌会一直留在库里。
        requestMapper.update(null, Wrappers.<AiPublishRequest>lambdaUpdate()
                .eq(AiPublishRequest::getId, draft.getId())
                .set(AiPublishRequest::getMemberId, memberId)
                .set(AiPublishRequest::getStatus, "SUBMITTED")
                .set(AiPublishRequest::getBizTable, target.table())
                .set(AiPublishRequest::getBizId, bizId)
                .set(AiPublishRequest::getConfirmToken, null));
        draft.setConfirmToken(null);

        log.info("发布已提交待审核：requestNo={} 表={} 主键={}",
                draft.getRequestNo(), target.table(), bizId);
        return new WriteResult(true, successMessage(target, bizId), bizId);
    }

    private Long insertChuzu(Long memberId, Map<String, Object> payload) {
        JxbChuzu row = new JxbChuzu();
        row.setCustomerId(memberId);
        row.setFirstCateId(asLong(payload.get("machineType")));
        row.setTonnage(asString(payload.get("tonnage")));
        row.setOwnerType(asString(payload.get("ownerType")));
        row.setRent(asString(payload.get("rent")));
        row.setPhone(asString(payload.get("phone")));
        row.setFactoryDate(asInteger(payload.get("factoryYear")));
        row.setDetailAddress(asString(payload.get("detailAddress")));
        row.setRemark(asString(payload.get("remark")));

        Object area = payload.get("area");
        row.setProvinceId(PublishValueValidator.regionPart(area, "provinceId"));
        row.setCityId(PublishValueValidator.regionPart(area, "cityId"));
        row.setDistrictId(PublishValueValidator.regionPart(area, "districtId"));

        row.setStatus(CHUZU_STATUS_AVAILABLE);
        row.setAuditStatus(AUDIT_PENDING);
        row.setDelFlag("0");
        row.setCreateTime(LocalDateTime.now());

        chuzuWriteMapper.insert(row);
        return row.getId();
    }

    private Long insertQiuzu(Long memberId, Map<String, Object> payload) {
        JxbQiuzu row = new JxbQiuzu();
        row.setCustomerId(memberId);
        row.setEquipmentType(asString(payload.get("equipmentType")));
        row.setProjectType(asString(payload.get("projectType")));
        row.setDuration(asString(payload.get("duration")));
        row.setPayType(asString(payload.get("payType")));
        row.setTrailerFee(asString(payload.get("trailerFee")));
        row.setRent(asString(payload.get("rent")));
        row.setPhone(asString(payload.get("phone")));
        row.setRemark(asString(payload.get("remark")));

        Object area = payload.get("area");
        row.setProvinceId(PublishValueValidator.regionPart(area, "provinceId"));
        row.setCityId(PublishValueValidator.regionPart(area, "cityId"));
        row.setDistrictId(PublishValueValidator.regionPart(area, "districtId"));

        row.setStatus(QIUZU_STATUS_ONGOING);
        row.setAuditStatus(AUDIT_PENDING);
        row.setDelFlag("0");
        row.setCreateTime(LocalDateTime.now());

        qiuzuWriteMapper.insert(row);
        return row.getId();
    }

    private static String successMessage(PublishTarget target, Long bizId) {
        return "已提交成功，编号 " + bizId + "。\n"
                + "这条" + target.label() + "信息现在处于**待审核**状态，平台审核通过后才会出现在列表里。\n"
                + "审核一般在 1 个工作日内完成，结果可以在\"我的发布\"里查看。";
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInteger(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

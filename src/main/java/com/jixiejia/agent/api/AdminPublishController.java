package com.jixiejia.agent.api;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.api.dto.PublishBindingRequest;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.auth.RequireRole;
import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiPublishRequestMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.publish.PublishMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布相关的管理接口。需要 ADMIN 角色。
 *
 * <p>两块内容：
 * <ul>
 *   <li><b>账号绑定</b>——AI 账号必须绑定平台会员才能发布，否则落库时
 *       {@code customer_id} 不知道该填谁。没绑定就发布，等于把设备挂到不相干的人名下，
 *       所以这里是硬前置；</li>
 *   <li><b>待审核列表</b>——AI 代发的信息一律进待审核，人工审过才上线。
 *       这是方案里 M9「发布审核台」的后端接口，页面复用即可。</li>
 * </ul>
 */
@Tag(name = "管理-发布", description = "账号绑定与发布审核，需 ADMIN")
@RestController
@RequestMapping("/api/admin/publish")
@RequireRole(CurrentUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminPublishController {

    private final PublishMemberService memberService;
    private final AiUserMapper userMapper;
    private final AiPublishRequestMapper requestMapper;

    @Operation(summary = "绑定平台会员",
            description = "把 AI 账号绑到平台会员上，之后该账号发布的信息才会挂到正确的人名下")
    @PostMapping("/bind")
    public ResponseEntity<?> bind(@RequestBody PublishBindingRequest request) {
        if (request.aiUserId() == null || request.memberId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "aiUserId 与 memberId 都不能为空"));
        }

        AiUser user = userMapper.selectById(request.aiUserId());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", 404, "message", "AI 账号不存在：" + request.aiUserId()));
        }

        memberService.link(request.aiUserId(), request.memberId());
        return ResponseEntity.ok(Map.of("code", 200,
                "message", "已绑定：" + user.getUsername() + " -> 会员 " + request.memberId()));
    }

    @Operation(summary = "账号绑定情况", description = "看某个 AI 账号绑到了哪个会员")
    @GetMapping("/binding")
    public ResponseEntity<?> binding(@RequestParam Long aiUserId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiUserId", aiUserId);
        result.put("memberId", memberService.resolve(aiUserId).orElse(null));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "待审核发布列表", description = "AI 代发、还没人工审核的信息")
    @GetMapping("/requests")
    public ResponseEntity<?> pendingRequests(@RequestParam(defaultValue = "20") int limit) {
        List<AiPublishRequest> rows = requestMapper.selectList(
                Wrappers.<AiPublishRequest>lambdaQuery()
                        .eq(AiPublishRequest::getStatus, "SUBMITTED")
                        .orderByDesc(AiPublishRequest::getId)
                        .last("limit " + Math.max(1, Math.min(limit, 100))));

        return ResponseEntity.ok(rows.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestNo", r.getRequestNo());
            item.put("targetTable", r.getBizTable());
            item.put("bizId", r.getBizId());
            item.put("memberId", r.getMemberId());
            item.put("payload", r.getPayload());
            item.put("submitTime", r.getUpdateTime());
            return item;
        }).toList());
    }
}

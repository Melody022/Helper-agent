package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 注册表，对应 ai_agent。
 *
 * <p>capabilities 是 JSON 数组，取值见 {@link com.jixiejia.agent.tool.ToolCapability}，
 * 决定这个 Agent 能拿到哪些工具；priority 数值越大越优先；is_fallback=1 的兜底 Agent
 * 只在没有其它 Agent 匹配时才被选中。
 */
@Data
@TableName("ai_agent")
public class AiAgent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Agent 标识，如 EquipmentAgent */
    private String agentKey;

    private String agentName;

    /** Spring Bean 名 */
    private String agentBean;

    private String description;

    /** 能力标识数组，JSON 形如 ["chuzu","qiuzu"] */
    private String capabilities;

    /** 匹配优先级，越大越优先 */
    private Integer priority;

    /** 是否兜底 0 否 1 是 */
    private Integer isFallback;

    /** 0 启用 1 停用 */
    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String remark;
}

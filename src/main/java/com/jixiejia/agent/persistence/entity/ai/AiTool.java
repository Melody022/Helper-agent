package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 工具注册表，对应 ai_tool。
 *
 * <p>内容由 {@link com.jixiejia.agent.tool.ToolRegistry} 从代码里标注了 {@code @Tool}
 * 的方法扫描生成——代码是 tool_name / capability / description / param_schema 的唯一来源，
 * 手工在库里改这些字段下次启动会被覆盖。但 {@code status} 和 {@code remark} 保留人工设置，
 * 便于在管理台临时停用某个工具。
 */
@Data
@TableName("ai_tool")
public class AiTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具名，即 @Tool(name)，全局唯一 */
    private String toolName;

    /** 能力标识 equipment/chuzu/qiuzu/news */
    private String capability;

    private String displayName;

    /** 工具描述，供模型理解何时调用 */
    private String description;

    /** 入参 JSON Schema，由 @ToolParam 反射生成 */
    private String paramSchema;

    /** 实现 Bean 名 */
    private String implBean;

    /** 0 启用 1 停用 */
    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String remark;
}

package com.jixiejia.agent.persistence.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jixiejia.agent.persistence.entity.ai.AiAgent;

/**
 * Agent 注册表，对应 ai_agent 表。可读写。
 */
public interface AiAgentMapper extends BaseMapper<AiAgent> {
}

package com.jixiejia.agent.persistence.mapper.ai;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jixiejia.agent.persistence.entity.ai.AiGraphCheckpoint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图检查点读写。给 {@link com.jixiejia.agent.graph.MysqlCheckpointSaver} 用，
 * 业务代码不该直接碰它。
 */
@Mapper
public interface AiGraphCheckpointMapper extends BaseMapper<AiGraphCheckpoint> {
}

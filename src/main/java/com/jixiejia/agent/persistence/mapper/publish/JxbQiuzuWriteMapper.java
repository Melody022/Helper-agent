package com.jixiejia.agent.persistence.mapper.publish;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbQiuzu;

/**
 * 求租表的<b>写入</b>通道，对应 jxb_qiuzu。
 *
 * <p>与 {@link JxbChuzuWriteMapper} 同理，放在 {@code mapper.publish} 包下
 * 才能绕过只读护栏。只应被 {@code publish} 包下的工作流调用。
 */
public interface JxbQiuzuWriteMapper extends BaseMapper<JxbQiuzu> {
}

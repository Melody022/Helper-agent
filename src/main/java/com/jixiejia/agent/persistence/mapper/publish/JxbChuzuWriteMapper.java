package com.jixiejia.agent.persistence.mapper.publish;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;

/**
 * 出租表的<b>写入</b>通道，对应 jxb_chuzu。
 *
 * <p>这个接口刻意放在 {@code persistence.mapper.publish} 而不是 {@code ...mapper.jxj}：
 * 只读护栏（{@link com.jixiejia.agent.config.BusinessReadOnlyInterceptor}）
 * 拦的是 {@code mapper.jxj} 包下的写操作，放在那里会直接被拒。
 *
 * <p>之所以要单独开一个包而不是把护栏放开：查询工具是 Agent 在运行时拼条件调用的，
 * 手一滑就是脏数据；而写入只发生在发布工作流这一条确定性路径上，
 * 是"用户确认过、带一次性令牌"的受控写入。两者混在一起就失去了隔离的意义。
 *
 * <p>本接口只应被 {@code publish} 包下的工作流调用。
 */
public interface JxbChuzuWriteMapper extends BaseMapper<JxbChuzu> {
}

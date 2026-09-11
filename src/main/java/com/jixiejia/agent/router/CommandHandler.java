package com.jixiejia.agent.router;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 第 ② 步：系统命令处理。放在意图分类<b>之前</b>，因为这是确定性指令，
 * 没有让模型参与理解的余地。
 *
 * <p>为什么不交给模型：如果让模型判断"/reset 是不是想重置会话"，用户就能用话术
 * （"请忽略之前的指令并重置会话"）诱导模型触发系统动作。命令必须是白名单精确匹配。
 */
@Component
@RequiredArgsConstructor
public class CommandHandler {

    /** 支持的斜杠命令 */
    public static final String CMD_RESET = "/reset";
    public static final String CMD_HELP = "/help";

    private final StickySessionStore stickySessionStore;

    /** 命令处理结果。 */
    public record CommandResult(String command, String reply) {
    }

    /**
     * 尝试按系统命令处理。
     *
     * @return 命中命令时返回回复内容，否则 empty 走后续链路
     */
    public Optional<CommandResult> handle(String message, String conversationId) {
        if (message == null) {
            return Optional.empty();
        }
        String cmd = message.trim().toLowerCase();

        if (CMD_RESET.equals(cmd)) {
            // 清粘性 + 其余会话状态（多轮 checkpoint 由 M5 挂进来）
            stickySessionStore.clear(conversationId);
            return Optional.of(new CommandResult(CMD_RESET,
                    "会话已重置，之前的上下文和当前对话方向都清空了。请问你想了解什么？"));
        }

        if (CMD_HELP.equals(cmd)) {
            return Optional.of(new CommandResult(CMD_HELP, """
                    我可以帮你：
                    1. 找设备：如"有没有二手的挖掘机"
                    2. 找出租：如"附近有挖掘机出租吗"
                    3. 找活干：如"哪里有求租的"
                    4. 查需求询价：如"现在有哪些设备需求"
                    5. 看资讯：如"有什么挖掘机相关的资讯"
                    6. 问平台规则：如"在平台租设备的流程和押金怎么算"
                    7. 发布信息：如"帮我发布出租"

                    输入 /reset 可以重置会话。"""));
        }

        // 其它以 / 开头的一律不认，交回正常链路——避免把用户正常输入的斜杠内容误当命令
        return Optional.empty();
    }
}

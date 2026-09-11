package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于 LangGraph4j ReAct 图的 Agent 基类。
 *
 * <p>用 {@code langgraph4j-springai-agentexecutor} 提供的 {@link ReactAgent} 构图：
 * 它已经实现好了"调模型 → 若模型要调工具则执行工具 → 把结果回灌模型 → 再调模型"
 * 这个循环，也就是方案里的 Agent ReAct。自己手写这套循环要处理边界条件一大堆，
 * 用现成的更稳。
 *
 * <p>关于构图开销：同一个 (Agent, 工具集合) 只编译一次并缓存。
 * 编译出来的图是无状态的——每轮对话的状态通过输入 Map 传进去，
 * 所以可以安全地在并发请求间复用。缓存键里必须带工具集合，
 * 因为不同角色拿到的工具不同，用同一张图就等于绕过了白名单。
 *
 * <p>提示词不在代码里，见 {@link PromptLibrary}——改措辞不用动 Java。
 */
@Slf4j
public abstract class AbstractReactAgent implements BizAgent {

    /** 工具集合 -> 编译好的图。键相同的请求复用同一张图。 */
    private final Map<String, CompiledGraph<MessagesState<Message>>> graphCache = new ConcurrentHashMap<>();

    protected final LlmClients clients;
    protected final PromptLibrary prompts;

    protected AbstractReactAgent(LlmClients clients, PromptLibrary prompts) {
        this.clients = clients;
        this.prompts = prompts;
    }

    /**
     * 本 Agent 的提示词在 {@code classpath:prompts/} 下的文件名（不含 .md）。
     * 公开出来是为了让运维/管理台能直接看到"这个 Agent 用的是哪个提示词文件"。
     */
    public abstract String promptKey();

    /** 本 Agent 的系统提示词，从提示词库读取。 */
    protected String systemPrompt() {
        return prompts.get(promptKey());
    }

    /** 执行失败时的兜底话术。 */
    protected String fallbackReply() {
        return "抱歉，我这边查询出了点问题，请稍后再试，或者回复\"转人工\"联系客服。";
    }

    @Override
    public String reply(AgentContext context) {
        try {
            List<ToolCallback> tools = context.tools();
            CompiledGraph<MessagesState<Message>> graph = graphCache.computeIfAbsent(
                    cacheKey(tools), key -> compile(tools));

            List<Message> messages = new ArrayList<>();
            if (context.history() != null) {
                messages.addAll(context.history());
            }
            messages.add(new UserMessage(context.userText()));

            return graph.invoke(Map.of(MessagesState.MESSAGES_STATE, messages))
                    .map(AbstractReactAgent::lastAssistantText)
                    .filter(text -> text != null && !text.isBlank())
                    .orElse(fallbackReply());

        } catch (Exception e) {
            // Agent 执行失败必须降级成一句人话，不能把异常抛给用户
            log.error("Agent {} 执行失败", agentKey(), e);
            return fallbackReply();
        }
    }

    private CompiledGraph<MessagesState<Message>> compile(List<ToolCallback> tools) {
        try {
            StateGraph<MessagesState<Message>> graph = ReactAgent.<MessagesState<Message>>builder()
                    .chatModel(clients.mainModel())
                    .defaultSystem(systemPrompt())
                    .tools(tools == null ? List.of() : tools)
                    // 必须显式给状态序列化器，否则构图时就抛 NullPointerException。
                    // 用 Spring AI 专用的这个：它认识 Message / AssistantMessage 这些类型，
                    // 换成一版通用的 Jackson 序列化器会因为消息对象里有多态字段而失败。
                    .stateSerializer(new SpringAIJacksonStateSerializer<>(MessagesState::new))
                    .build();
            log.debug("Agent {} 构图完成，工具 {} 个", agentKey(), tools == null ? 0 : tools.size());
            return graph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent " + agentKey() + " 的图构建失败", e);
        }
    }

    /** 缓存键 = 工具名排序后拼接。工具集合不同就必须用不同的图。 */
    private static String cacheKey(List<ToolCallback> tools) {
        if (tools == null || tools.isEmpty()) {
            return "none";
        }
        return tools.stream()
                .map(t -> t.getToolDefinition().name())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    /** 取最后一条助手消息的正文。工具调用消息没有正文，会被跳过。 */
    private static String lastAssistantText(MessagesState<Message> state) {
        List<Message> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}

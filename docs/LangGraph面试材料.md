# LangGraph / LangGraph4j 面试材料

> **为什么要有这份**：简历上保留了 **LangGraph4j**（企业 JD 大量要求"熟悉 LangGraph"，
> 这是筛选关键词）。但 JD 里的 LangGraph 指 **Python 原版**，懂行的面试官会按
> **checkpointer / interrupt / 图级流式**这几个"招牌能力"往下问——而这几个本项目**恰好都没用**。
>
> **答不上来比不写这个词更糟**，因为它会坐实"只是跟风加了个关键词"。
> 这份材料的目标是：**让这个关键词扛得住深挖**。
>
> 最后更新：2026-09-12　｜　代码证据均标注 `文件:行号`，可现场翻

---

## 一、LangGraph 是什么（开场用）

**一句话定位**：**LangGraph 是「有状态的图编排框架」——它把 Agent 从「链」变成「图」。**

**为什么会有它**：
- LangChain 的 `AgentExecutor` 是个**黑盒**——你不知道它内部怎么循环、没法插桩、没法改路由策略；
- LCEL 是 **DAG（有向无环图）**，**做不了循环**；
- 而 Agent 的本质**恰恰就是循环**：想 → 调工具 → 看结果 → 再想。

所以要有一个**能循环、能分支、能回访节点、状态显式**的编排层。LangGraph 就是这个东西，
执行语义受 **Pregel 风格的 super-step** 启发。

> ⚠️ **一定要强调的一句**：**它不是"更高级的 LangChain"，而是低层原语。**
> LangChain 管流程，LangGraph 管状态与循环。
> 说清这个定位，比背 API 更能证明你懂。

---

## 二、三个核心概念（面试必答，且最容易答浅）

| 要素 | 是什么 | **容易答浅的地方** |
|---|---|---|
| **State** | 类型化的共享状态；节点返回的是**增量更新**，不是直接改 | 核心是 **reducer**——多个节点写同一个字段时怎么合并。默认**覆盖**，`add_messages` 是**追加 + 去重**（还能用 `RemoveMessage` 撤回） |
| **Node** | `(state) -> state_update` | 别说"函数封装"。它是**有明确输入输出契约的执行单元**，要考虑重试 / 超时 / 错误处理 |
| **Edge** | 控制流载体 | 别说"连接节点"。价值在**条件路由**与**循环控制**；`Command` 还能同时更新状态并跳转 |

**必须补上的两个机制**（说了这两个，档次立刻不一样）：

1. **super-step**：路由函数返回多个后继时，这些节点在**同一个 tick 内并行执行**，
   全部完成后才进入下一步——这是 map-reduce 的载体；
2. **`recursion_limit` 默认 25**：循环图必须有硬性终止条件（迭代计数 / error budget），
   否则抛 `GraphRecursionError`。
   → **这一条直接通向本项目的做法**（见第六节 6.1 的 `CompileConfig` 行）。

---

## 三、与 LangChain 的区别（最高频问题）

**一句话**：**LangChain 管流程，LangGraph 管状态与循环。**

| 维度 | LangChain | LangGraph |
|---|---|---|
| 流程 | 单向（线性） | 任意方向（图），**支持循环** |
| 状态管理 | 有限 | **类型安全的共享 State** |
| 条件分支 | 基础 | 动态路由 |
| 多 Agent 协作 | 繁琐 | **设计核心** |
| 循环 | 难实现 | 原生支持 |

**加分回答**（说出来说明你不是只跟着教程走）：
LangChain 常被诟病三点——**过度抽象导致调试困难、版本迭代快 API 不稳定、依赖过重**。
所以真正的判断是：**什么场景该用 LangChain（快速搭 RAG 管道）、
什么场景该绕过它直接用 LangGraph 或原生 API（要精确控制流程）。**

---

## 四、四个"招牌能力"——JD 真正想考的是这些

### 4.1 Checkpointer / 持久化 ⭐ 最核心

在**每个 super-step 边界**保存状态快照，按 **thread** 组织。

- **`thread_id` 是主键兼持久游标**：不指定它，既存不了状态、也没法恢复；
- **它支撑四件事**：**断点续跑**（故障恢复）、**时间旅行调试**（重放/分叉历史）、
  **跨交互记忆**、**Human-in-the-Loop**；
- **状态操作 API**：`get_state` / `get_state_history` / `update_state`（`update_state`
  生成新检查点、**不修改原检查点**）；
- **durability 三档**：`exit`（性能最好，中途崩溃不可恢复）/ `async` / `sync`（最持久、有开销）；
- **后端**：InMemory（实验）、SQLite（本地）、Postgres（生产）、以及 MySQL / Redis / Mongo 实现。

### 4.2 Interrupt / Human-in-the-Loop

`interrupt()` 在节点内任意位置调用 → 抛 `GraphInterrupt` 暂停 → 值暴露给调用方 →
用 `Command(resume=...)` 恢复，**恢复值成为 `interrupt()` 的返回值**。

> ⚠️ **必考的三个坑**（能主动说出来是加分项）：
> 1. **恢复时节点从头重跑**，不是从 `interrupt` 那一行续上——所以它**之前的副作用必须幂等**；
> 2. **不要包在裸 `try/except` 里**（会把特殊异常吞掉，中断传不出去）；
> 3. **不要用非确定性逻辑循环调用 `interrupt`**（多个中断按**索引**匹配恢复值，顺序必须一致）；
>    正确做法是每次节点调用只调一次，用**条件边**循环回来。
>
> 另外区分：**静态中断**（`interrupt_before/after`，编译时设置）只推荐用于调试；
> **动态中断**（`interrupt()`）才是 HITL 的正解。

### 4.3 Streaming

要分清三种粒度：**token 级**（逐字输出）、**状态级**（`stream.values`，每步后的状态）、
**节点级 / 事件级**（`stream_events`，节点开始结束、中断负载）。
HITL 场景下要用事件级流式，才能在处理中断的同时并发消费消息块。

### 4.4 多 Agent 拓扑

| 模式 | 结构 | 取舍 |
|---|---|---|
| **Supervisor** | 中央协调者路由到 worker，worker 做完回到 supervisor | **最推荐入门**：路由决策集中、**易审计**。代价是**权力集中**，容易成瓶颈，也容易变成"worker 职责划不清"的借口 |
| **Swarm / P2P** | Agent 之间直接交接，无中央路由 | 灵活，但所有权、路由、调试都更复杂 |
| **Hierarchical** | 图里有图（嵌套子图） | 适合企业级，配持久化时要注意 checkpoint 命名空间 |

> 实践共识：**从 Supervisor 起步，只有当工作流确实需要时才转向点对点委派。**

---

## 五、LangGraph4j 与 LangGraph 的对照（Python ↔ Java）

**LangGraph4j 是 Python 版 LangGraph 的 Java 移植**（官方描述：*"a porting of original
LangGraph from LangChain AI project in Java fashion"*），可与 LangChain4j / Spring AI 集成。

| Python LangGraph | LangGraph4j | 本项目用的 |
|---|---|---|
| `StateGraph` / `TypedDict` state | `StateGraph` + `AgentState`（`Map<String,Object>` 包装） | ✅ |
| reducer（`add_messages` / `operator.add`） | `Channel` / `Channels.base()`（覆盖）、`Channels.appender()`（追加） | ✅ **用了 appender**（见第六节） |
| Checkpointer（`BaseCheckpointSaver`） | `BaseCheckpointSaver`（`MemorySaver` / `MySQLSaver` / `PostgresSaver` / `RedisSaver`） | ❌ |
| `thread_id` 断点续跑 | `RunnableConfig.threadId()` / `checkPointId()` | ❌ |
| `StateSerializer` | `SpringAIJacksonStateSerializer`（Spring AI）/ `ObjectStreamStateSerializer` | ✅ **两种都用了** |
| `interrupt` / `Command(resume=)` | 同类能力 | ❌ |
| 子图 subgraph | 同类能力 | ❌ |

**结论**：**概念一一对应，Python 版的知识可以直接迁移过来讲。**
差异主要在序列化器与存储后端，以及**成熟度不及 Python 版**（业界普遍这么认为）。
**这正是"我用 Java 版但照样能聊 Python 版"的依据。**

---

## 六、本项目对照表 ⭐ 这一节是核心

> 全项目**有 8 个 Java 文件** import 了 `org.bsc.langgraph4j`：
> `AbstractReactAgent`、`CompositeGraph`、`CompositeState`、
> `RoutingGraph` / `RoutingState`（路由链状态图，2026-09-13）、
> `PublishGraph` / `PublishState`（发布 HITL 图，2026-09-13）、
> `MysqlCheckpointSaver`（自实现的检查点存取器）。
> 依赖见 `pom.xml:23`（版本 `1.8.27`）、`pom.xml:91-106`（三个 artifact）。

### 6.1 用了什么（每个都有证据）

| 用了 | 位置 | 说明 |
|---|---|---|
| **四张独立的 `StateGraph`** | `AbstractReactAgent`、`CompositeGraph`、`RoutingGraph`、`PublishGraph` | ReAct 图 ×4 + 跨域综合图 + 路由链图 + 发布 HITL 图 |
| **预置 `ReactAgent`** | `AbstractReactAgent` | ReAct 循环由库预置，业务侧不手写节点 |
| **手写 `StateGraph`** | `CompositeGraph`、`RoutingGraph`、`PublishGraph` | `addNode` / `addEdge` / `addConditionalEdges` / `compile` |
| **条件边 `addConditionalEdges`** | `RoutingGraph` 4 条、`PublishGraph` 2 条 | 见 6.2 |
| **`EdgeMappings` 标签映射** | 同上 | **注意它在 `org.bsc.langgraph4j.utils` 包**，不在 `action` 包 |
| **`CompileConfig` 步数上限** | `RoutingGraph`(15)、`PublishGraph`(8) | 库默认 25 |
| **⭐ checkpointer（持久化）** | **`PublishGraph` + `MysqlCheckpointSaver`** | **自实现的 MySQL 存取器**，见 6.2 |
| **⭐ interrupt（human-in-the-loop）** | **`PublishGraph.awaitConfirm`** | 见 6.2 |
| **`GraphInput.resume`** | `PublishGraph.resume(...)` | 从挂起点恢复 |
| **appender 通道（reducer）** | `CompositeState`、`RoutingState` | 见 6.3 |
| **两种序列化器** | `AbstractReactAgent` / `CompositeGraph` 等 | `SpringAIJacksonStateSerializer` / `ObjectStreamStateSerializer` |
| **图缓存 + 并发复用** | 各图 | 见 6.4 |

### 6.2 两个"能讲出深度"的实现细节 ⭐⭐

**① 并行扇出：LangGraph4j 没有公开 API**

`CompositeGraph` 的类注释（`CompositeGraph.java:40-44`）写得很清楚：

> LangGraph4j 没有"并行节点"的公开 API，也不支持 `addEdge(List, String)`。
> 它的机制是——**给同一个源节点挂多条出边**，编译期自动生成一个合成并行节点
> （`__PARALLEL__(源id)`），用它把所有分支的结果合并后，再走那条唯一的出边。
> **所以：所有分支必须汇合到同一个后继，否则 `compile()` 直接抛错；扇出的源也不能再用条件边。**

实现上就是：`START` 挂 N 条出边（`:128`），所有 `domain_*` 都 `addEdge` 到 `SYNTHESIZE`（`:129`）。

> **这是"我真读过源码"的最强信号**——因为它不是文档里的内容，是编译期行为。

**② reducer 的真实使用：为什么是 `appender` 而不是 `base`**

`CompositeState` 的通道定义（`CompositeState.java:74-82`）：

```java
schema.put(RESULTS, Channels.appender(ArrayList::new));   // ← 多个域并行写同一个 key
schema.put(MESSAGE, Channels.<String>base(() -> ""));     // ← 单写，用 base 就够
```

注释（`CompositeState.java:17-19`）解释了原因：
**多个域节点并行写同一个 key，普通通道"后写覆盖先写"会互相覆盖、只剩最后一个；
appender 通道把每次写入追加进同一个 `List`，所以并行安全。**

**这段可以直接回答"reducer 是干嘛的"**——不是背定义，是讲"我们为什么必须用它、
不用会出什么错"。

**③ 顺带一个设计取舍：固定挂满节点，而不是动态构图**

`CompositeGraph.java:46-49` 注释：5 个 Agent 的非空子集有 **31 种**，
动态构图要么每次重编译、要么缓存 31 张图。
改法是**固定挂满所有 Agent 节点**，本轮没被选中的节点立刻
`return CompletableFuture.completedFuture(Map.of())`（`:187-189`）返回空增量。
**一张图、空转开销可忽略。**

**④ 自实现的 MySQL checkpointer + interrupt（发布确认的 human-in-the-loop）** ⭐⭐

这是 LangGraph 三个招牌能力里最难自己讲清的那个，现在项目里真有了。

**为什么自己写 checkpointer**：核心包只自带 `MemorySaver`（进程一重启就没了）
和 `FileSystemSaver`（写本地磁盘）。前者和项目已经写明的原则冲突——
`PublishFormService` 的类注释里就写着"状态放内存里，进程一重启就没了，
用户会莫名其妙地'从头再来'"；后者会把图状态混进知识库原件那个落盘目录，不好排查。
而 `AbstractCheckpointSaver` **只有 4 个抽象方法**，自己实现更干净：
新建 `ai_graph_checkpoint` 表 + `MysqlCheckpointSaver`，
序列化**复用图自己的 `StateSerializer`**，不另起一套——这样"state 里能放什么"
由图的通道定义统一决定，不会出现"图能跑但存不进库"这种只在挂起时才暴露的问题。

**interrupt 的协议**（这个版本没文档，是实验测出来的，现在钉在 `InterruptProtocolTest` 里）：

| 结论 | 说明 |
|---|---|
| `interrupt(nodeId, state)` 是**节点执行前的预检** | 返回非空就挂起，**节点体不执行** |
| 挂起时 `invoke` **返回的是有值的状态** | 别拿 `isEmpty()` 判挂起——判据看检查点的 `next_node_id` |
| `GraphInput.resume(payload)` 把答复**并进 state**，**从挂起节点继续** | 它之前的节点不重跑 |

> **一个值钱的元教训**：头一版是用"打印出来看一眼"来确认协议的，
> 结果**得出了两个错误结论**——因为空字符串和 `Optional.empty` 在打印里长得一样。
> **协议这种东西不能用肉眼看，得写成断言。** 现在这三条固化成了测试，库升级时会先红。

**踩过的坑**（详见踩坑第 56 条）：
`isSuspended` 把 `__END__` 当成"挂起"（图正常跑完时 `next_node_id` 写的是 `__END__` 而不是 null），
导致每轮都走恢复分支，**症状是"模型被反复调用、答非所问"**；
以及一次多余的 `advance` 重新生成了确认令牌，**"用户明明抄对了却说抄错"**。

### 6.3 并发与复用（面试官爱问"图能不能共享"）

- **能**，因为**编译出的图是无状态的**：每轮状态通过 `invoke` 的输入 `Map` 传入；
- 单域图缓存在 `ConcurrentHashMap`，键 = **工具名排序后拼接**（`AbstractReactAgent.java:113-121`）。
  ⚠️ **键必须带工具集合**——不同角色拿到的工具白名单不同，复用同一张图**等于绕过白名单**；
- 跨域图在 `@PostConstruct` 里**启动时编译一次**（`CompositeGraph.java:81` 的 `volatile compiled`）。

### 6.4 没用什么（**每条都配体面的理由**）

| 能力 | 证据 | 为什么没用 / 怎么答 |
|---|---|---|
> **⚠️ 2026-09-13 更新**：**checkpointer 和 interrupt 现在都用上了**（发布流程的 human-in-the-loop），
> 见 6.2。所以下面这张表里只剩"仍然没用"的几项——**别再说"我们没用 checkpointer"，那是老话**。

| 仍然没用 | 证据 | 为什么 / 怎么答 |
|---|---|---|
| **图级流式** | **0 处 `stream()`**；只有 `ChatStreamController` 的 token/事件级 SSE | 我们的 SSE 是**应用层**推 `route`/`answer`/`done` 事件。代码注释（`ChatStreamController.java:37-39`）已标明：逐字流式需要把图的 `StreamingChatGenerator` 接进 ReAct 图内部，**这是明确的待办** |
| **subgraph** | 无 `addSubgraph` | `CompositeGraph` 是**独立图**，不是挂在父图下的子图（由 `ChatService.runComposite` 直接调用）。**我们只有一层编排，没有嵌套需求** |
| **`Command` / `Send` 原语** | 无 | ⚠️ 注意别搞混：项目里的 `router/CommandHandler` 是**应用层自定义的"系统命令"**（`/reset`），**不是** LangGraph4j 的 `Command` |
| **hook** | 无 `addCallModelHook` / `addExecuteToolsHook` | 我们没有"在模型调用前后插一刀"的需求；可观测性走的是**审计表**（`ai_audit_log`），不在图里做 |
| **`ConversationContextPolicy`** | 全项目 0 处命中 | 库里有这个 API，**我们没接**。会话历史用的是自己实现的定长滑窗（`ChatService.HISTORY_ROUNDS = 5`） |

> ⚠️ **一个必须自己知道的坑**：`pom.xml:91` 的依赖注释写着
> 「状态图编排（**interrupt/resume、checkpoint saver、stream**）」——
> **这三个能力项目里一个都没用**。那是写依赖时抄来的说明文字。
> 另外 `CommandHandler.java:45`、`ConversationMemory.java:22-23` 里还有
> 「M5 会把 checkpoint 挂进来」这类**过期 TODO**。
> **如果有人问"你们 checkpoint 怎么用的"，被这些注释带偏就会当场翻车——
> 记住权威答案是「没用，状态外置」。**

---

## 七、LangGraph 专项面试问答

**Q：LangGraph 和 LangChain 什么区别？**
> **LangChain 管流程，LangGraph 管状态与循环。** LCEL 是 DAG，做不了循环；
> 而 Agent 的本质就是循环。LangGraph 是低层原语，不是"更高级的 LangChain"——
> 它管的是状态、循环、分支、回访。
> 加分项：说清 LangChain 被诟病的三点（过度抽象、API 不稳、依赖重），
> 以及"什么场景该绕过它"。

**Q：StateGraph 的三要素？reducer 是干嘛的？**
> State / Node / Edge。State 的要点在 **reducer**——它决定**多个节点写同一个字段时怎么合并**。
> 默认是覆盖，`add_messages` 是追加+去重。
> **落到我们项目**：跨域图里 `RESULTS` 这个 key 会被多个域节点**并行写**，
> 如果用默认通道就会"后写覆盖先写、只剩最后一个"，所以我们用的是
> **appender 通道**（`Channels.appender`）——把每次写入追加进同一个 List。
> **这不是背概念，是我们踩过的必要性。**

**Q：checkpointer 解决什么问题？你们用了吗？**
> 它在**每个 super-step 边界**存快照，以 `thread_id` 为游标，支撑四件事：
> 断点续跑、时间旅行调试、跨交互记忆、HITL。
> **我们用了**，在发布流程上。checkpointer 是 interrupt 的**前提**——
> 图挂起后下一轮请求得从挂起点恢复，"挂在哪"必须有地方存，没有它 interrupt 根本不成立。
> **库里只自带 `MemorySaver`（进程重启就没了）和 `FileSystemSaver`（写本地磁盘），
> 都不合适**，所以自己实现了一个 `MysqlCheckpointSaver`——
> `AbstractCheckpointSaver` 只有 4 个抽象方法，新建一张 `ai_graph_checkpoint` 表即可，
> 序列化复用图自己的 `StateSerializer`。
> **分工讲清楚**：checkpointer 管"图跑到哪了"（可以随时清），
> `ai_publish_request` 管"用户填了什么"（后台审核的依据，不能丢）。

**Q：interrupt 怎么用？恢复的时候有什么坑？**
> **通用三条**：① 恢复时节点会被重新执行，所以它之前的副作用**必须幂等**；
> ② 不能包在裸 `try/except` 里；③ 多个中断按**索引**匹配恢复值，顺序必须一致。
>
> **落到我们项目（`PublishGraph.awaitConfirm`），实测出来的三条更具体**：
> ① `interrupt(nodeId, state)` 是**节点执行前的预检**——返回非空就挂起，**节点体不执行**；
> ② **挂起时 `invoke` 返回的是有值的状态**，别拿 `isEmpty()` 判挂起，
> 判据要看检查点的 `next_node_id`；
> ③ `GraphInput.resume(payload)` 把答复**并进 state**，并**从挂起节点继续**，之前的节点不重跑。
>
> **一个值钱的教训**：这三条最初是用"打印出来看一眼"确认的，
> 结果**得出了两个错误结论**——空字符串和 `Optional.empty` 在打印里长得一样。
> **协议这种东西不能用肉眼看，得写成断言**，现在固化在 `InterruptProtocolTest` 里。

**Q：并行扇出怎么实现？**（**这题答好等于证明你真用过**）
> LangGraph4j **没有公开的并行 API**——翻遍 `StateGraph` 只有 `addEdge(String, String)`，
> 没有多源汇合的重载。真相是**隐式**的：**给同一个源节点挂多条出边**，
> 编译期自动生成一个合成并行节点（`__PARALLEL__(源id)`）；
> 而且**所有分支必须汇合到同一个后继**，否则 `compile()` 直接抛错；
> 扇出的源也不能再用条件边。
> 我们跨域综合就是这么做的：`START` 挂 N 条出边，所有分支汇到 `synthesize`。

**Q：Supervisor 模式和你项目的编排方式有什么异同？**
> **相同点**：都是中心化路由、都为了可审计。
> **不同点**：Supervisor 的**协调者是个 LLM 节点**，由它决定下一个调谁、什么时候结束；
> 而**我们的扇出是代码决定的**——路由层判"命中 ≥2 个业务域"就直接扇出去，
> 各域并行跑完汇到 `synthesize`。
> **取舍**：代码扇出**成本可预测、不会死循环、可审计**；
> 代价是**不能动态决定要不要叫某个 Agent**。
> 如果把协调者也换成 LLM，那就变成 Supervisor 了——**我们没这么选，因为客服场景要的是确定性**。

**Q：循环怎么防死？**
> LangGraph 靠 `recursion_limit`（默认 25），超了抛 `GraphRecursionError`；
> 另外路由逻辑里应该设硬性终止条件。
> **落到我们项目**：路由状态图用 `CompileConfig.builder().recursionLimit(15)` **显式卡死**——
> 链路最长 7 步，留一倍余量。
> **这里修过一次、值得讲**：以前 `graph.invoke(map)` 从不传 `RunnableConfig`、
> 也没设 `CompileConfig`，靠的是**库的默认值 25**，对一条 7 步的链来说等于没设。
> 把骨架搬进图的时候顺手补上了——**"依赖框架兜底"和"自己的预算控制"是两回事。**
> ⚠️ **还没做的**：4 张业务 Agent 的 ReAct 图**仍然用默认 25**，没跟着收紧。
> 它们才是真正会多轮调工具的地方，理论上更该设——这条照实说。

---

## 八、一页速览（面试前 5 分钟看这段）

**讲 LangGraph 的三句话**：
1. **它是低层「有状态编排」框架，把 Agent 从「链」变成「图」**——因为 Agent 的本质就是循环，而 LCEL 是 DAG 做不了循环。
2. **三要素是 State / Node / Edge，State 的核心是 reducer**（多节点写同一字段的合并规则）。
3. **四个招牌能力：checkpointer、interrupt、流式、多 Agent 拓扑。**

**讲自己项目的三句话**：
1. **四张 `StateGraph`**：4 个业务 Agent 的 ReAct 图（库预置 `ReactAgent`）、
   跨域综合图（并行扇出 + appender 通道汇总）、**路由链图**（7 节点 + 4 条条件边）、
   **发布 HITL 图**（4 节点 + 1 个 interrupt 挂起点）。
2. **checkpointer 和 interrupt 都用了**，在发布确认上——而且 checkpointer 是**自己实现的
   MySQL 版**（库里只有内存和本地磁盘的）。LangGraph 的招牌能力**全都在项目里有落点**。
3. **LangGraph4j 是 Python 版的 Java 移植，概念一一对应**，所以 Python 版的题我也能答。

**必须主动说的边界**（别等被问出来）：
- **图级流式没用**（SSE 是应用层的事件粒度，不是图节点级）
- **subgraph 没用**（`CompositeGraph` 是独立图，不是挂在父图下的子图）
- **`Command` / `Send` 原语没用**
- **路由图标了 `recursionLimit(15)`、发布图 8，但 4 张 ReAct 图仍用默认 25**——没收紧
- `pom.xml` 里那句"interrupt/resume、checkpoint saver、stream"当年是**抄来的注释**
  （现在前两个真做了，stream 还没做）

**两个项目的分工话术**（被问到"你怎么没做 X"时用）：
> 我这个客服 Agent 没做上下文压缩、长期记忆这些——**单轮短、状态外置**就够了。
> 我在另一个通用 Agent 项目里做了完整的记忆分层，因为**那边的任务天然是长程的**。
> **这两个取舍不是能力差距，是任务性质决定的。**

---

## 参考

- [Checkpointers — Docs by LangChain](https://docs.langchain.com/oss/python/langgraph/checkpointers)
- [Human-in-the-Loop — LangGraph](https://mintlify.wiki/langchain-ai/langgraph/concepts/human-in-the-loop)
- [Interrupts — Docs by LangChain](https://docs.langchain.org.cn/oss/python/langgraph/interrupts)
- [interrupt | langgraph API reference](https://reference.langchain.com/python/langgraph/types/interrupt)
- [LangGraph4j Getting Started](https://langgraph4j.github.io/langgraph4j/1.9/getting-started/)
- [langgraph4j/langgraph4j — DeepWiki 概览](https://deepwiki.com/langgraph4j/langgraph4j/1-overview)
- [Agent 架构与框架面试通关 15 问](https://gitcode.csdn.net/6a295a2410ee7a33f27a8d64.html)

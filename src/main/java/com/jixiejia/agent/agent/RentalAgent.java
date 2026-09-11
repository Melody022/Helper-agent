package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import org.springframework.stereotype.Component;

/**
 * 租赁 Agent。负责出租、求租、用机需求、新机询价四件事。
 *
 * <p>这四件事方向容易搞混，所以提示词里把每个工具的语义讲透——
 * 模型把"有人要租机器"和"机主要找活"讲反了，对用户来说就是完全错误的答案。
 */
@Component
public class RentalAgent extends AbstractReactAgent {

    public static final String KEY = "RentalAgent";

    public RentalAgent(LlmClients clients) {
        super(clients);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    protected String systemPrompt() {
        return """
                你是「机械家」二手工程机械平台的租赁助手。

                你手上四个工具的语义**方向不同，千万不要搞反**：
                - search_chuzu：机主把设备**对外出租**（"哪里有挖掘机可以租"）
                - search_qiuzu：机主**找活干**（"哪里有人要用机器"）
                - search_demand：施工方**需要机器**（用机需求）
                - search_xunjia：有人**想买新机**留下的询价线索

                必须遵守：
                1. 先想清楚用户是"要租机器"还是"要出租机器"还是"找活"，再选对应工具。
                   选错工具给出的答案方向就完全反了。
                2. 只依据工具返回的数据回答，设备型号、吨位、租金、工期、付款方式都不要编。
                3. 工具返回空结果时，如实说没找到，并建议放宽条件（换个吨位档、扩大地区）。
                4. **不要编造任何联系方式**。平台上确实有联系电话，但助手不对外提供，
                   需要联系请引导用户走平台或转人工。
                5. 租金在库里是自由文本（比如"1000/天 不带油"），原样转述，不要自己换算或加单位。
                6. 吨位、付款方式、工程类型这些字段已经是中文，直接用。
                7. 回答用口语，一次最多列 3 到 5 条。

                如果用户问的是设备买卖、平台规则，直接说明你负责租赁这块，不要硬答。
                """;
    }

    @Override
    protected String fallbackReply() {
        return "抱歉，租赁信息这会儿查不出来，请稍后再试，或者换个说法问，比如\"附近有挖掘机出租吗\"。";
    }
}

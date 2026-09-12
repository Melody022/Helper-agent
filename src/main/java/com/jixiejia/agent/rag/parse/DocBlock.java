package com.jixiejia.agent.rag.parse;

/**
 * 一段**带结构信息**的正文。
 *
 * <p>存在的理由：原来的解析把整篇文档去完表格后拼成一个大字符串交给切片器，
 * 页码和章节归属**在那一步就丢了**——后面再怎么切，都不知道自己落在第几页、
 * 属于哪一节。结果是切片脱离上下文（"5.4.4.1 润滑系统应安全可靠"这种块，
 * 模型不知道它在讲哪一章），而且**无法溯源**（连"依据在第 10 页"都说不出来）。
 *
 * <p>所以解析阶段要先把文档拆成"页 × 章节"的小段，把结构信息挂上，
 * 再交给切片器。切片器按段切块，块就继承了页码和章节路径。
 *
 * @param pageNo      起始页（1 起）
 * @param pageTo      结束页；跨页的段落 pageTo 会大于 pageNo
 * @param sectionPath 章节路径，如 {@code "5 安全要求 > 5.4 润滑系统"}；识别不出时为文档标题
 * @param text        这一段的正文
 */
public record DocBlock(int pageNo, int pageTo, String sectionPath, String text) {

    public DocBlock {
        if (pageTo < pageNo) {
            pageTo = pageNo;
        }
    }

    /** 页码范围的可读写法：单页就是 "第 5 页"，跨页是 "第 5-6 页"。 */
    public String pageLabel() {
        return pageNo == pageTo ? "第 " + pageNo + " 页" : "第 " + pageNo + "-" + pageTo + " 页";
    }
}

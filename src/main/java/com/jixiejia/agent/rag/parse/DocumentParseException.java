package com.jixiejia.agent.rag.parse;

/**
 * 文档解析失败。携带一句<b>能给用户看</b>的中文说明。
 *
 * <p>解析链路上挂着两个外部依赖（LiteParse CLI、LibreOffice），
 * 它们挂掉的原因对用户来说是有意义的（"没装 LibreOffice"和"文件损坏"要区别对待），
 * 所以这里刻意把 message 写成可直接回给前端的文案，而不是堆栈信息。
 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

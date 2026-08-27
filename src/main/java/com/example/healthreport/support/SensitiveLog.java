package com.example.healthreport.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 体检报告隐私正文的<b>唯一</b>日志出口，级别固定为 DEBUG。
 *
 * <p>OCR 文本、LLM-A 响应正文、原始文件名、异常指标与过敏原这类内容排障时确实需要看得见
 * ——不然「这份报告为什么抽得差」只能靠猜。允许它们进日志，但必须满足两个条件。</p>
 *
 * <p><b>① 独立 logger，不是各自类的 logger。</b> 名字是 {@code SENSITIVE_LOGGER_NAME}，
 * 与包结构无关。这样把 {@code logging.level.com.example.healthreport} 整体调成 DEBUG
 * ——排查状态机、线程池时很常见的动作——<b>不会</b>顺带把报告全文打开。
 * 想看隐私正文必须显式写出这个 logger 名，是一个有意识的决定。</p>
 *
 * <p><b>② 默认 OFF。</b> 见 {@code application.properties} 的「隐私正文日志」一节。
 * 打开它等于让报告原文落到日志文件、并被日志采集系统集中留存与索引，
 * 而 §0.3-③ 特意不让 MySQL 存这些内容——从日志再存一遍就把那条约束绕过去了。
 * 仅限排障期临时打开，用完关掉。</p>
 *
 * <p><b>凭证永远不走这里。</b> {@code apiKey}、{@code Authorization} 头在任何级别、
 * 任何 logger 上都不记录——那不是隐私分级问题，是凭证泄露。</p>
 *
 * <p><b>图片字节永远不走这里。</b> 一页 300DPI 的 Base64 是几 MB，
 * 打出来既没有可读性又会打爆日志管道；发给模型的图片一律以占位符代替。</p>
 */
public final class SensitiveLog {

    /** 隐私正文 logger 名；与包结构无关，必须被显式配置才会生效。 */
    public static final String SENSITIVE_LOGGER_NAME = "HEALTH_REPORT_SENSITIVE";

    private static final Logger LOG = LoggerFactory.getLogger(SENSITIVE_LOGGER_NAME);

    private SensitiveLog() {
    }

    /**
     * 是否已开启隐私正文日志。
     * <p>调用方在<b>拼接代价不为零</b>时（例如拼请求体摘要）先判这个，
     * 避免关闭状态下仍然付出字符串拼接成本。</p>
     */
    public static boolean enabled() {
        return LOG.isDebugEnabled();
    }

    /** 记录一条隐私正文；参数占位符与 SLF4J 一致。 */
    public static void debug(String format, Object... argumentArray) {
        LOG.debug(format, argumentArray);
    }
}

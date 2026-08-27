package com.example.healthreport.support;

import lombok.Getter;

/**
 * 资源归属校验失败。
 * <p>不存在、归属不符、已删除和已过期统一为同一异常形状，避免泄露资源状态。</p>
 */
@Getter
public class OwnershipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对外统一的 HTTP 状态码。 */
    private final int httpStatus = 404;

    /** 对外统一的业务失败码。 */
    private final FailCode failCode = FailCode.RESULT_EXPIRED;

    /**
     * 构造不携带内部失败原因的统一异常。
     */
    public OwnershipException() {
        super("资源不存在或已失效");
    }
}

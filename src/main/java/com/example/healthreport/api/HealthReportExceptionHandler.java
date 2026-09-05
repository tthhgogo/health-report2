package com.example.healthreport.api;

import com.example.healthreport.api.dto.CommonResponse;
import com.example.healthreport.support.BusinessException;
import com.example.healthreport.support.FailCode;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一、无敏感内容的错误响应。
 *
 * <p><b>业务失败一律靠抛 {@link BusinessException}</b>（2026-09-05 定），这里只有一条业务分支：
 * {@code retCode} 取 {@code exceptionCode}，{@code retMsg} 取错误内容，{@code data} 恒为 {@code null}。
 * 移植到接入方时本类可整体删除，由对方的全局处理器接住同一个异常。</p>
 *
 * <p>剩下三条是<b>容器在进入 Controller 之前抛出的</b>，业务代码没有机会抛 BusinessException，
 * 只能在这里补齐同一形状。<b>HTTP 状态码一律 200</b>，成败只看 {@code retCode}。</p>
 */
@RestControllerAdvice
public class HealthReportExceptionHandler {

    /** 业务异常的唯一出口：全链路只抛 BusinessException，这里不再逐类映射。 */
    @ExceptionHandler(BusinessException.class)
    public CommonResponse<Void> handleBusinessException(BusinessException exception) {
        return CommonResponse.response(exception.getExceptionCode(), exception.getErrorMessage(), null);
    }

    /**
     * 请求体超出 multipart 限额，在进入 Controller 前由容器抛出。
     * 必须与报文畸形分开：这里让用户换小文件重试是有意义的。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public CommonResponse<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return CommonResponse.fail(FailCode.FILE_TOO_LARGE.name());
    }

    /**
     * multipart 报文本身无法解析（边界符损坏、Content-Type 与实际内容不符等）。
     * 【不得复用 FILE_TOO_LARGE】——那会让前端提示用户压缩文件，而压多小都解决不了报文错误。
     * 注意顺序：MaxUploadSizeExceededException 是本异常的子类，Spring 按最具体的处理器匹配。
     */
    @ExceptionHandler(MultipartException.class)
    public CommonResponse<Void> handleMalformedMultipart(MultipartException exception) {
        return CommonResponse.fail(FailCode.MALFORMED_REQUEST.name());
    }

    /** 请求字段数量、空值或重复 fileId 统一返回 INVALID_REQUEST。 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public CommonResponse<Void> handleInvalidRequest(Exception exception) {
        return CommonResponse.fail("INVALID_REQUEST");
    }
}

package com.example.healthreport.api;

import com.example.healthreport.api.dto.ErrorResponse;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.OwnershipException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 上传与建任务接口的统一、无敏感内容错误响应。 */
@RestControllerAdvice
public class HealthReportExceptionHandler {

    /** 返回任务定义的业务失败码。 */
    @ExceptionHandler(HealthReportException.class)
    public ResponseEntity<ErrorResponse> handleHealthReportException(HealthReportException exception) {
        ErrorResponse response = new ErrorResponse(exception.getFailCode().name(), exception.getTaskId());
        return ResponseEntity.status(exception.getHttpStatus()).body(response);
    }

    /** 文件归属失败沿用统一的资源不可见响应，避免泄露文件是否存在。 */
    @ExceptionHandler(OwnershipException.class)
    public ResponseEntity<ErrorResponse> handleOwnershipException(OwnershipException exception) {
        ErrorResponse response = new ErrorResponse(exception.getFailCode().name(), null);
        return ResponseEntity.status(exception.getHttpStatus()).body(response);
    }

    /**
     * 请求体超出 multipart 限额，在进入 Controller 前由容器抛出。
     * 必须与报文畸形分开：这里让用户换小文件重试是有意义的。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception) {
        ErrorResponse response = new ErrorResponse(FailCode.FILE_TOO_LARGE.name(), null);
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * multipart 报文本身无法解析（边界符损坏、Content-Type 与实际内容不符等）。
     * 【不得复用 FILE_TOO_LARGE】——那会让前端提示用户压缩文件，而压多小都解决不了报文错误。
     * 注意顺序：MaxUploadSizeExceededException 是本异常的子类，Spring 按最具体的处理器匹配。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMalformedMultipart(MultipartException exception) {
        ErrorResponse response = new ErrorResponse(FailCode.MALFORMED_REQUEST.name(), null);
        return ResponseEntity.badRequest().body(response);
    }

    /** 请求字段数量、空值或重复 fileId 统一返回 400。 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", null));
    }
}

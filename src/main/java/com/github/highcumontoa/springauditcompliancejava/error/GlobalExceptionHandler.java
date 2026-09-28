package com.github.highcumontoa.springauditcompliancejava.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ErrorCode code = ex.getErrorCode();
        log.warn("业务拒绝: code={}, message={}", code.name(), ex.getMessage());
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.VALIDATION_MISSING_FIELD.getDefaultMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_MISSING_FIELD.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_MISSING_FIELD, detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_INVALID_JSON.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_INVALID_JSON,
                        ErrorCode.VALIDATION_INVALID_JSON.getDefaultMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String message = "参数 " + ex.getName() + " 取值非法: "
                + (ex.getValue() == null ? "null" : ex.getValue());
        return ResponseEntity.status(ErrorCode.VALIDATION_INVALID_RULE.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_INVALID_RULE, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}

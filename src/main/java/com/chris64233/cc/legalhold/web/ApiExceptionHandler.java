package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.DeletionBlockedException;
import com.chris64233.cc.legalhold.service.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
    }

    @ExceptionHandler({ConflictException.class, DeletionBlockedException.class,
            DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException ex) {
        List<String> reasons = ex instanceof DeletionBlockedException blocked
                ? blocked.getReasons()
                : List.of(ex instanceof DataIntegrityViolationException
                        ? "并发冲突或唯一约束冲突，请用同一业务号重试" : ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), reasons);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<String> reasons = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "请求参数校验失败", reasons);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      List<String> reasons) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "reasons", reasons));
    }
}

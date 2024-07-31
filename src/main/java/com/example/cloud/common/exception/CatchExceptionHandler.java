package com.example.cloud.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestControllerAdvice(annotations = {RestController.class})
public class CatchExceptionHandler {
    @ExceptionHandler(value = { HttpMessageNotReadableException.class })
    public void exceptionHandle(HttpServletRequest req) {
        log.error("    URL   => " + req.getRequestURL() + " | HttpMessageNotReadableException Error | NONE 처리 됐음");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handValidationExceptions(MethodArgumentNotValidException validException){
        Map<String, String> errors = new HashMap<>();
        validException.getBindingResult().getAllErrors()
                .forEach(c -> errors.put("message", c.getDefaultMessage()));
        // ((FieldError) c).getField() => error가 난 field 값을 ResponseEntity
        // 의 key값으로 활용하고 싶다면 "message" 대신에 이걸 넣자!
        return ResponseEntity.badRequest().body(errors);
    }
}

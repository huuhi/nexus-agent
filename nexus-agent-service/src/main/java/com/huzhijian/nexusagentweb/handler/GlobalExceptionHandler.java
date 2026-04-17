package com.huzhijian.nexusagentweb.handler;

import com.huzhijian.nexusagentweb.exception.NotSupportException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(NotSupportException.class)
    public ResponseEntity<String> handleNotSupport(NotSupportException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public  ResponseEntity<String> handleValidation(ValidationException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> handleNotSupport(IOException ex){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<String> handleNotSupport(UnauthorizedException ex){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }
}

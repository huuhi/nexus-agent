package com.huzhijian.nexusagentweb.handler;

import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.NotSupportException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.vo.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    public Result handleNotSupport(NotSupportException ex){
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public  Result handleValidation(ValidationException ex){
        return Result.error(ex.getMessage());
    }
    @ExceptionHandler(IOException.class)
    public Result handleNotSupport(IOException ex){
        return Result.error(ex.getMessage());
    }
    @ExceptionHandler(UnauthorizedException.class)
    public Result handleNotSupport(UnauthorizedException ex){
        return Result.error(ex.getMessage());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValid(MethodArgumentNotValidException ex){
        return  Result.error(ex.getBindingResult().getFieldError().getDefaultMessage());
    }
    @ExceptionHandler(NotFoundException.class)
    public Result handleNotFound(NotFoundException ex){
        return Result.error(ex.getMessage());
    }
}

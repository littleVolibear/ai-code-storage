package com.example.plandeduce.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 进度条接口的统一异常处理。
 * 这里把业务参数类异常收敛成稳定的 HTTP 响应，避免前端只看到 500 和后端日志堆栈。
 */
@RestControllerAdvice
public class PlanDeduceExceptionHandler {

    /**
     * 处理业务参数错误。
     * 当前主要覆盖非法 fullSaveInterval、非法 dbName 等场景，对外统一返回 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException exception) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", exception.getMessage());
        return result;
    }
}

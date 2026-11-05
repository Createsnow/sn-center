package com.adtek.framework.web;

import com.adtek.common.core.ErrorCode;
import com.adtek.common.core.Result;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在 Spring MVC 之外（安全过滤链的 401/403）写出与 {@link GlobalExceptionHandler} 相同格式的失败信封。 */
@Component
public class ResultResponseWriter {
    private final JsonMapper jsonMapper;

    public ResultResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(
                response.getOutputStream(),
                Result.fail(errorCode).withTraceId(TraceContext.current()));
    }
}

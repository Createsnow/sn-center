package com.adtek.framework.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.Result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new ProbeController())
                        .setControllerAdvice(
                                new GlobalExceptionHandler(), new ResultTraceIdAdvice())
                        .addFilters(new TraceIdFilter())
                        .build();
    }

    @Test
    void successCarriesTheSameTraceIdAsTheResponseHeader() throws Exception {
        MvcResult result =
                mvc.perform(get("/probe/ok"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.key").value(Result.SUCCESS_KEY))
                        .andExpect(jsonPath("$.data").value("9007199254740993"))
                        .andExpect(header().string(TraceContext.HEADER, matchesTraceId()))
                        .andReturn();

        String traceId = result.getResponse().getHeader(TraceContext.HEADER);
        assertThat(result.getResponse().getContentAsString()).contains(traceId);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void businessFailureUsesTheErrorCodeStatusAndArguments() throws Exception {
        mvc.perform(get("/probe/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.CONFLICT.code()))
                .andExpect(jsonPath("$.key").value("error.common.conflict"))
                .andExpect(jsonPath("$.args.name").value("demo"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void validationFailureListsFieldNamesWithoutEchoingValues() throws Exception {
        mvc.perform(
                        post("/probe/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"" + "x".repeat(65) + "\",\"code\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_REQUEST.code()))
                .andExpect(jsonPath("$.args.fields").value("code,name"));
    }

    @Test
    void malformedBodyIsAnInvalidRequest() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_REQUEST.code()));
    }

    @Test
    void frameworkErrorsMapByStatus() throws Exception {
        mvc.perform(post("/probe/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.METHOD_NOT_ALLOWED.code()));
        mvc.perform(post("/probe/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.code()));
    }

    @Test
    void duplicateKeyIsAConflict() throws Exception {
        mvc.perform(get("/probe/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.CONFLICT.code()));
    }

    @Test
    void dependencyFailureIsServiceUnavailable() throws Exception {
        mvc.perform(get("/probe/dependency"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DEPENDENCY_UNAVAILABLE.code()));
    }

    @Test
    void unexpectedFailureDoesNotLeakTheMessage() throws Exception {
        MvcResult result =
                mvc.perform(get("/probe/unexpected"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value(CommonErrorCode.INTERNAL_ERROR.code()))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-detail");
    }

    @Test
    void unknownStatusesFallBackToGenericCodes() {
        assertThat(GlobalExceptionHandler.byStatus(406)).isEqualTo(CommonErrorCode.INVALID_REQUEST);
        assertThat(GlobalExceptionHandler.byStatus(502)).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
    }

    private static org.hamcrest.Matcher<String> matchesTraceId() {
        return org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}");
    }

    record ProbeDTO(@NotBlank String code, @Size(max = 64) String name) {}

    @RestController
    static class ProbeController {
        @GetMapping("/probe/ok")
        Result<String> ok() {
            return Result.ok("9007199254740993");
        }

        @GetMapping("/probe/business")
        Result<Void> business() {
            throw new BusinessException(CommonErrorCode.CONFLICT, Map.of("name", "demo"));
        }

        @PostMapping("/probe/validate")
        Result<Void> validate(@Valid @RequestBody ProbeDTO body) {
            return Result.ok();
        }

        @GetMapping("/probe/duplicate")
        Result<Void> duplicate() {
            throw new DuplicateKeyException("uk_demo_code");
        }

        @GetMapping("/probe/dependency")
        Result<Void> dependency() {
            throw new DataAccessResourceFailureException("redis down");
        }

        @GetMapping("/probe/unexpected")
        Result<Void> unexpected() {
            throw new IllegalStateException("secret-detail");
        }
    }
}

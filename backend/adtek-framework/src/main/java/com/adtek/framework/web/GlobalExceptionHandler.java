package com.adtek.framework.web;

import com.adtek.common.constant.ErrorCodeConstants;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Web 错误翻译边界；业务失败不泄露底层原因，服务器错误只在受控日志留存。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Void>> business(BusinessException error, HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,error.getError().code());
        var response = ResponseEntity.status(error.getError().httpStatus());
        if (error.getError().httpStatus() == 429) {
            String retryAfter = error.getParams().getOrDefault("retryAfter", "1");
            response.header("Retry-After", retryAfter.matches("[0-9]{1,6}") ? retryAfter : "1");
        }
        return response.body(Result.failure(error.getError(), error.getParams(), TraceFilter.current(request)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, org.springframework.beans.TypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Result<Void>> invalid(Exception error, HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.INVALID_ARGUMENT.code());
        return ResponseEntity.badRequest().body(Result.failure(
                ErrorCodeConstants.INVALID_ARGUMENT, Map.of(), TraceFilter.current(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Result<Void>> missing(HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.NOT_FOUND.code());
        return ResponseEntity.status(404).body(Result.failure(
                ErrorCodeConstants.NOT_FOUND, Map.of(), TraceFilter.current(request)));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Result<Void>> method(HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.METHOD_NOT_ALLOWED.code());
        return ResponseEntity.status(405).body(Result.failure(ErrorCodeConstants.METHOD_NOT_ALLOWED,Map.of(),TraceFilter.current(request)));
    }

    /** 锁竞争和唯一键冲突保留409；其他完整性错误仍是服务故障，不能将审计落库失败伪装成可重试业务拒绝。 */
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
            org.springframework.dao.CannotSerializeTransactionException.class,org.springframework.dao.DuplicateKeyException.class})
    ResponseEntity<Result<Void>> conflict(HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.CONFLICT_RETRY.code());
        return ResponseEntity.status(409).body(Result.failure(ErrorCodeConstants.CONFLICT_RETRY,Map.of(),TraceFilter.current(request)));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Result<Void>> forbidden(HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.FORBIDDEN.code());
        return ResponseEntity.status(403).body(Result.failure(ErrorCodeConstants.FORBIDDEN, Map.of(), TraceFilter.current(request)));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    ResponseEntity<Result<Void>> unauthenticated(HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.UNAUTHENTICATED.code());
        return ResponseEntity.status(401).body(Result.failure(ErrorCodeConstants.UNAUTHENTICATED, Map.of(), TraceFilter.current(request)));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.QueryTimeoutException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<Result<Void>> dependency(Exception error, HttpServletRequest request) {
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.DEPENDENCY_UNAVAILABLE.code());
        LOG.error("Data dependency unavailable, traceId={}", TraceFilter.current(request), error);
        return ResponseEntity.status(503).body(Result.failure(
                ErrorCodeConstants.DEPENDENCY_UNAVAILABLE, Map.of(), TraceFilter.current(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception error, HttpServletRequest request) {
        // MyBatis 包装拦截器的拒绝；只还原明确的数据权限错误，不把其他数据库异常伪装为业务错误。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business
                    && business.getError().equals(com.adtek.framework.constant.ErrorCodeConstants.SCOPE_RULE_MISSING)) {
                return business(business, request);
            }
        }
        LOG.error("Unhandled request failure, traceId={}", TraceFilter.current(request), error);
        com.adtek.framework.audit.RequestAuditState.error(request,ErrorCodeConstants.INTERNAL_ERROR.code());
        return ResponseEntity.internalServerError().body(Result.failure(
                ErrorCodeConstants.INTERNAL_ERROR, Map.of(), TraceFilter.current(request)));
    }
}

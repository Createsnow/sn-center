package com.adtek.framework.web;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.ErrorCode;
import com.adtek.common.core.Result;
import com.adtek.framework.audit.FailureAuditPort;
import com.adtek.framework.security.AuthPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局异常处理：所有失败统一为 {@link Result} 信封，HTTP 状态取自错误码，不用 200 表示失败。
 *
 * <p>不向客户端返回异常消息、SQL 或堆栈；只有非预期异常与依赖故障记录日志（日志带 traceId）。校验失败只返回字段名，不回显输入值。
 *
 * <p>已登录账户的 {@code /api} 写请求失败时，经 {@link FailureAuditPort}（若有提供方）在独立事务中记一条失败痕迹；留痕失败不影响响应。
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectProvider<FailureAuditPort> failureAudit;

    public GlobalExceptionHandler() {
        this(null);
    }

    @Autowired
    public GlobalExceptionHandler(ObjectProvider<FailureAuditPort> failureAudit) {
        this.failureAudit = failureAudit;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException exception) {
        return respond(exception.errorCode(), exception.args());
    }

    /** 请求体或查询参数绑定校验失败：参数 fields 为按字母排序、逗号分隔的字段名。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> binding(BindException exception) {
        String fields =
                exception.getFieldErrors().stream()
                        .map(FieldError::getField)
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining(","));
        return respond(
                CommonErrorCode.INVALID_REQUEST,
                fields.isEmpty() ? Map.of() : Map.of("fields", fields));
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        TypeMismatchException.class
    })
    public ResponseEntity<Result<Void>> invalid(Exception exception) {
        return respond(CommonErrorCode.INVALID_REQUEST, Map.of());
    }

    /** 服务用例内的授权失败；匿名请求在安全过滤链已返回 401，不会到达这里。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden(AccessDeniedException exception) {
        return respond(CommonErrorCode.FORBIDDEN, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> unauthenticated(AuthenticationException exception) {
        return respond(CommonErrorCode.UNAUTHENTICATED, Map.of());
    }

    /** 超长、格式不符等数据约束问题（不是唯一键冲突）：请求本身的问题，重试也不会成功。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> integrity(DataIntegrityViolationException exception) {
        if (exception instanceof DuplicateKeyException) {
            return respond(CommonErrorCode.CONFLICT, Map.of());
        }
        log.warn("Data integrity violation: {}", exception.getMostSpecificCause().getMessage());
        return respond(CommonErrorCode.INVALID_REQUEST, Map.of("fields", "body"));
    }

    /** 唯一约束冲突与锁/版本冲突返回 409，不静默覆盖、不自动重试。 */
    @ExceptionHandler({DuplicateKeyException.class, ConcurrencyFailureException.class})
    public ResponseEntity<Result<Void>> conflict(Exception exception) {
        return respond(CommonErrorCode.CONFLICT, Map.of());
    }

    /** MySQL 不可用：返回依赖失败，不放行请求。 */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Result<Void>> dependency(DataAccessResourceFailureException exception) {
        log.warn("Dependency unavailable", exception);
        return respond(CommonErrorCode.DEPENDENCY_UNAVAILABLE, Map.of());
    }

    /**
     * 其余异常：Spring MVC 标准异常（实现 {@link ErrorResponse}，如 404 路由、405 方法、415 媒体类型、缺少参数）按其 HTTP
     * 状态映射为公共错误码； 其他均为非预期失败，记录日志并返回内部错误。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            int status = errorResponse.getStatusCode().value();
            if (status >= 500) {
                log.error("Request failed", exception);
            }
            return respond(byStatus(status), Map.of());
        }
        log.error("Unhandled request failure", exception);
        return respond(CommonErrorCode.INTERNAL_ERROR, Map.of());
    }

    static CommonErrorCode byStatus(int status) {
        return switch (status) {
            case 401 -> CommonErrorCode.UNAUTHENTICATED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> CommonErrorCode.CONFLICT;
            case 413 -> CommonErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> CommonErrorCode.TOO_MANY_REQUESTS;
            case 503 -> CommonErrorCode.DEPENDENCY_UNAVAILABLE;
            default ->
                    status >= 500
                            ? CommonErrorCode.INTERNAL_ERROR
                            : CommonErrorCode.INVALID_REQUEST;
        };
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode, Map<String, String> args) {
        audit(errorCode, args);
        return ResponseEntity.status(errorCode.httpStatus())
                .body(Result.fail(errorCode, args).withTraceId(TraceContext.current()));
    }

    private void audit(ErrorCode errorCode, Map<String, String> args) {
        if (failureAudit == null) {
            return;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return;
        }
        HttpServletRequest request = servlet.getRequest();
        if (!WRITE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")) {
            return;
        }
        AuthPrincipal principal = AuthPrincipal.current().orElse(null);
        FailureAuditPort port = failureAudit.getIfAvailable();
        if (principal == null || port == null) {
            return;
        }
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object body = request.getAttribute(RequestBodyCachingFilter.BODY_ATTRIBUTE);
        try {
            port.recordFailure(
                    principal,
                    request.getMethod(),
                    request.getRequestURI(),
                    template == null ? request.getRequestURI() : template.toString(),
                    body instanceof byte[] bytes ? bytes : null,
                    errorCode,
                    args);
        } catch (RuntimeException e) {
            // 留痕失败不能盖掉原来的错误响应
            log.warn("Failure audit failed", e);
        }
    }
}

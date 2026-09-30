package com.adtek.framework.audit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
/** 只在单个请求内去重；不能把 traceId 当业务幂等号，后台调用不隐式传播此状态。 */
public final class RequestAuditState {
    static final String RECORDED=RequestAuditState.class.getName()+".recorded";
    public static final String ERROR_CODE=RequestAuditState.class.getName()+".errorCode";
    private RequestAuditState() {}
    /** 必须在独立失败审计事务返回（已提交）之后调用；成功业务审计不能设置该标记。 */
    public static void failureCommitted() {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(RECORDED,Boolean.TRUE);
        }
    }
    public static void error(HttpServletRequest request,int code) { request.setAttribute(ERROR_CODE,code); }
}

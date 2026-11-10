package com.adtek.framework.web;

import com.adtek.common.constant.CommonErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * {@code /api} 写请求：先整包读入（超过 {@link #MAX_REQUEST_BODY} 直接 413，不再往下读），不超过 {@link #MAX_AUDIT_BODY}
 * 的请求体另存入请求属性 {@link #BODY_ATTRIBUTE}，供请求拒绝审计抽取白名单字段；之后请求体可照常重复读取。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyCachingFilter extends OncePerRequestFilter {
    public static final String BODY_ATTRIBUTE = RequestBodyCachingFilter.class.getName() + ".body";
    public static final int MAX_AUDIT_BODY = 64 * 1024;
    public static final long MAX_REQUEST_BODY = 50L * 1024 * 1024;
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ResultResponseWriter writer;

    public RequestBodyCachingFilter(ResultResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BODY) {
            writer.write(response, CommonErrorCode.PAYLOAD_TOO_LARGE);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        try (InputStream in = request.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > MAX_REQUEST_BODY) {
                    writer.write(response, CommonErrorCode.PAYLOAD_TOO_LARGE);
                    return;
                }
                buffer.write(chunk, 0, n);
            }
        }
        byte[] body = buffer.toByteArray();
        if (body.length <= MAX_AUDIT_BODY) {
            request.setAttribute(BODY_ATTRIBUTE, body);
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(
                    new InputStreamReader(
                            getInputStream(),
                            encoding == null
                                    ? StandardCharsets.UTF_8
                                    : java.nio.charset.Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}

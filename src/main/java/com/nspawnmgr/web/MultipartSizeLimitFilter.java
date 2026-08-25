package com.nspawnmgr.web;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Catches {@link MaxUploadSizeExceededException} before Spring Boot's own {@code ErrorPageFilter}
 * does, so an oversized upload (e.g. a package/ISO past
 * {@code spring.servlet.multipart.max-file-size} - see application.yml) reaches the browser as a
 * clean, actionable message instead of a generic Whitelabel error page - confirmed live, that's
 * exactly what happened before this filter existed (an admin's oversized ISO upload showed nothing
 * useful at all; the message {@link ApiExceptionHandler#handleUploadTooLarge} already wrote for
 * this exact case never actually ran, since a {@code @ControllerAdvice} only wraps handler
 * execution and this exception is thrown by {@code DispatcherServlet.checkMultipart()} *before*
 * any handler is ever dispatched to).
 *
 * <p>Registered at {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} (see
 * {@link com.nspawnmgr.config.WebFilterConfig}) deliberately, not
 * {@code Ordered.HIGHEST_PRECEDENCE} - filter execution nests like function calls, so the filter
 * closest to the servlet (highest order value) is the *first* to see an exception unwinding from
 * it, not the last. Boot's own {@code ErrorPageFilter} registers itself at (or near)
 * {@code HIGHEST_PRECEDENCE} specifically so it can wrap every other filter - to intercept an
 * exception before it ever reaches that outer catch block, this filter has to sit *inside* it,
 * i.e. later in the chain, not earlier.
 */
public class MultipartSizeLimitFilter extends OncePerRequestFilter {

    /** Shared with {@link ApiExceptionHandler#handleUploadTooLarge} so both places show the same
     *  text and get updated together if the configured limit (application.yml) ever changes again. */
    static final String UPLOAD_TOO_LARGE_MESSAGE = "File exceeds the 10GB upload limit.";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (MaxUploadSizeExceededException e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(UPLOAD_TOO_LARGE_MESSAGE);
            response.getWriter().flush();
        }
    }
}

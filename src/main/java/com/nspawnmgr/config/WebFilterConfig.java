package com.nspawnmgr.config;

import com.nspawnmgr.web.MultipartSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Plain servlet-container-level filter registrations - distinct from {@link SecurityConfig}'s own
 *  Spring Security filter chain, since {@link MultipartSizeLimitFilter} needs to sit relative to
 *  Boot's own {@code ErrorPageFilter} in the outer servlet filter chain, not Security's chain. */
@Configuration
public class WebFilterConfig {

    @Bean
    public FilterRegistrationBean<MultipartSizeLimitFilter> multipartSizeLimitFilterRegistration() {
        FilterRegistrationBean<MultipartSizeLimitFilter> registration = new FilterRegistrationBean<>(new MultipartSizeLimitFilter());
        // See MultipartSizeLimitFilter's own javadoc for why LOWEST_PRECEDENCE (innermost, closest
        // to the servlet) is correct here, not HIGHEST_PRECEDENCE.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}

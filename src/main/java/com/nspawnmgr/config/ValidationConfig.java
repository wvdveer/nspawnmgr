package com.nspawnmgr.config;

import com.nspawnmgr.service.UserMessages;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Overrides {@link ValidationAutoConfiguration}'s own default {@code Validator} bean (via Spring
 * Boot's standard {@code @ConditionalOnMissingBean} customization point) so a custom
 * {@code message = "{some.translation.key}"} on a bean-validation annotation (see the DTOs under
 * {@code web.dto}) resolves through {@link TranslatingMessageInterpolator} - and therefore through
 * {@link UserMessages}, for the current request's locale - instead of being treated as a literal
 * (curly braces and all) or looked up in a {@code ValidationMessages.properties} resource bundle
 * that doesn't exist in this codebase.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public LocalValidatorFactoryBean validator(UserMessages messages) {
        LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
        factoryBean.setMessageInterpolator(new TranslatingMessageInterpolator(messages));
        return factoryBean;
    }
}

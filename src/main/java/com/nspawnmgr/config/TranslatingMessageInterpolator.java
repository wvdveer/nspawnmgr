package com.nspawnmgr.config;

import com.nspawnmgr.service.UserMessages;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;

import javax.validation.MessageInterpolator;
import java.util.Locale;

/**
 * A {@code message = "{some.translation.key}"} on a bean-validation annotation (see the DTOs under
 * {@code web.dto}) is resolved via {@link UserMessages} for the current request's locale - the
 * standard javax.validation {@code {...}} convention, but pointed at nspawnmgr's own
 * {@code lang/*.json} tables instead of a {@code ValidationMessages.properties} resource bundle
 * (which this codebase doesn't have). Anything {@link UserMessages} doesn't recognize (rendered as
 * the {@code [[missing:...]]} marker - see {@link com.nspawnmgr.service.TranslationService#get}),
 * including every constraint annotation's own built-in default message (e.g. a bare {@code @NotBlank}
 * with no {@code message=} override, whose default template is itself
 * {@code "{javax.validation.constraints.NotBlank.message}"}), falls through to the real default
 * interpolator unchanged.
 */
public class TranslatingMessageInterpolator implements MessageInterpolator {

    private final UserMessages messages;
    private final MessageInterpolator delegate = new ResourceBundleMessageInterpolator();

    public TranslatingMessageInterpolator(UserMessages messages) {
        this.messages = messages;
    }

    @Override
    public String interpolate(String messageTemplate, Context context) {
        String translated = tryTranslate(messageTemplate);
        return translated != null ? translated : delegate.interpolate(messageTemplate, context);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        String translated = tryTranslate(messageTemplate);
        return translated != null ? translated : delegate.interpolate(messageTemplate, context, locale);
    }

    private String tryTranslate(String messageTemplate) {
        if (!messageTemplate.startsWith("{") || !messageTemplate.endsWith("}")) {
            return null;
        }
        String key = messageTemplate.substring(1, messageTemplate.length() - 1);
        String resolved = messages.get(key);
        return resolved.startsWith("[[missing:") ? null : resolved;
    }
}

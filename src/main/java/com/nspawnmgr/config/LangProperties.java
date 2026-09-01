package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where {@link com.nspawnmgr.service.TranslationService} reads its {@code *.json} translation
 *  files from - see that class's own javadoc for the file format. */
@ConfigurationProperties(prefix = "nspawnmgr.lang")
public record LangProperties(
        String dir
) {
}

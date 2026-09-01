package com.nspawnmgr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nspawnmgr.config.LangProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Loads every {@code *.json} translation file under {@code nspawnmgr.lang.dir} into memory once,
 * at construction (same "read-once-into-memory, no live-reload" shape as {@link SettingsService}'s
 * own snapshot - there's no existing {@code @PostConstruct}/lifecycle-hook precedent in this
 * codebase to mirror instead). Each file is named after its 2-letter language code (e.g.
 * {@code en.json}, {@code es.json}) and holds a single flat JSON object mapping dot-namespaced
 * message keys (e.g. {@code "nav.machines"}, {@code "error.container.notRunning"}) to a message
 * template. An admin adding a new file to that directory and restarting Tomcat makes that language
 * available with no rebuild - {@link #availableLocales()} reflects whatever's actually on disk,
 * not a hardcoded list.
 *
 * <p>{@code en.json} is the mandatory fallback both for a locale with no file at all and for a key
 * missing from an otherwise-present locale's file - startup fails loudly if it's missing or fails
 * to parse, since every other guarantee this class makes depends on it existing.
 *
 * <p>Placeholders are a deliberately naive {@code {0}}, {@code {1}}, ... substitution (plain
 * literal replacement, NOT {@link java.text.MessageFormat} - that class treats a bare {@code '} as
 * its own escape character, which would silently mangle the many translated strings that contain a
 * real apostrophe). The same naive substitution is reimplemented client-side in {@code i18n.js} -
 * keeping both sides deliberately simple is what keeps their behavior identical.
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** The guaranteed-present fallback locale - see this class's own javadoc. */
    public static final String DEFAULT_LOCALE = "en";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, String>> translationsByLocale;

    public TranslationService(LangProperties langProperties) {
        Path dir = Path.of(langProperties.dir());
        this.translationsByLocale = loadAll(dir);
        if (!translationsByLocale.containsKey(DEFAULT_LOCALE)) {
            throw new IllegalStateException(
                    "lang/" + DEFAULT_LOCALE + ".json is missing or failed to parse under " + dir
                            + " - it's the mandatory fallback every other language falls back to, so nspawnmgr can't start without it.");
        }
        log.info("Loaded translations for locale(s) {} from {}", translationsByLocale.keySet(), dir);
    }

    /** Every locale code actually available right now (has a real, parsed {@code <code>.json}
     *  file), sorted for a stable Profile-page dropdown order. Always includes {@link #DEFAULT_LOCALE}. */
    public TreeSet<String> availableLocales() {
        return new TreeSet<>(translationsByLocale.keySet());
    }

    public boolean isAvailable(String locale) {
        return locale != null && translationsByLocale.containsKey(locale.toLowerCase(Locale.ROOT));
    }

    /** Locale code -> that language's own name for itself (its {@code meta.languageName} key,
     *  e.g. {@code "es"->"Español"}), sorted by locale code - backs the Profile page's language
     *  picker. Every shipped file carries this key; a new admin-dropped file should too, but a
     *  missing one just falls back to {@link #DEFAULT_LOCALE}'s own value like any other key. */
    public Map<String, String> availableLocalesWithNames() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String locale : availableLocales()) {
            result.put(locale, get(locale, "meta.languageName"));
        }
        return result;
    }

    /** Looks up {@code key} in {@code locale}'s table, falling back to {@link #DEFAULT_LOCALE} if
     *  the locale itself isn't available or doesn't have that particular key. Renders visibly as
     *  {@code [[missing:key]]} rather than blank/throwing if the key exists in neither - a gap
     *  during development should be obvious, not silently swallowed. */
    public String get(String locale, String key, Object... args) {
        String template = lookup(locale, key);
        if (template == null) {
            return "[[missing:" + key + "]]";
        }
        return substitute(template, args);
    }

    /** The full key->template table for a locale (falling back to {@link #DEFAULT_LOCALE} if the
     *  requested one isn't available) - used to embed the active locale's whole table into each
     *  page for {@code i18n.js} to read client-side, rather than one lookup per JS-built string. */
    public Map<String, String> allFor(String locale) {
        Map<String, String> table = translationsByLocale.get(normalized(locale));
        return table != null ? table : translationsByLocale.get(DEFAULT_LOCALE);
    }

    private String lookup(String locale, String key) {
        Map<String, String> table = translationsByLocale.get(normalized(locale));
        String template = table != null ? table.get(key) : null;
        if (template == null) {
            template = translationsByLocale.get(DEFAULT_LOCALE).get(key);
        }
        return template;
    }

    private static String normalized(String locale) {
        return locale == null ? DEFAULT_LOCALE : locale.toLowerCase(Locale.ROOT);
    }

    private static String substitute(String template, Object[] args) {
        if (args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    private Map<String, Map<String, String>> loadAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Translation directory {} does not exist - only {} (if any) will be available", dir, DEFAULT_LOCALE);
            return Collections.emptyMap();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String locale = fileNameWithoutExtension(file).toLowerCase(Locale.ROOT);
                try {
                    Map<String, String> table = objectMapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {
                    });
                    result.put(locale, table);
                } catch (IOException e) {
                    log.error("Failed to parse translation file {} - locale '{}' will be unavailable", file, locale, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to list translation directory {}", dir, e);
        }
        return result;
    }

    private static String fileNameWithoutExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot == -1 ? name : name.substring(0, dot);
    }
}

-- User.preferredLanguage - 2-letter language code (e.g. "en", "es") matching a filename under the
-- lang/ folder (see TranslationService). Nullable: NULL means auto-detect from the browser's own
-- Accept-Language header on every request rather than a fixed per-user choice - see
-- LocaleResolutionService.
ALTER TABLE users ADD COLUMN preferred_language VARCHAR(2);

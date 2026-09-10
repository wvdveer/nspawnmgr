-- SettingsService.authLoginUrlForRequest() - comma-separated list of additional external origins
-- (scheme://host[:port], no path) that get their own login-redirect/returnTo target built from
-- their own scheme+host instead of auth_login_url's fixed origin, matched against the incoming
-- request's Host header. Lets nspawnmgr be reached correctly through more than one public
-- hostname (e.g. a reverse proxy in front of it) without disturbing auth_login_url's own existing
-- behavior for whichever origin it already points at - see SecurityConfig.loginRedirectUrl().
-- Null/blank means "no additional origins", unchanged from before this feature existed.
ALTER TABLE app_settings ADD COLUMN auth_additional_trusted_origins VARCHAR(1000);

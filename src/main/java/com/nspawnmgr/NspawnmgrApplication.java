package com.nspawnmgr;

import com.nspawnmgr.bootstrap.DbConnectionSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import java.util.Arrays;

// UserDetailsServiceAutoConfiguration is excluded because it's inert here (httpBasic/formLogin
// are disabled in SecurityConfig; auth is entirely via CookiePreAuthFilter) but otherwise logs a
// noisy "generated security password" warning on every startup.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class NspawnmgrApplication extends SpringBootServletInitializer {

    // Deployed as a WAR into an external Tomcat (alongside Guacamole's own webapp, real or the
    // tools/fake-guacamole-server stand-in); this method is what the container's
    // ServletContainerInitializer discovery calls — see tools/scripts/start-dev-stack.sh /
    // start-real-stack.sh. main() below is otherwise inert here: spring-boot-starter-tomcat is
    // "provided" scope (the external container supplies it), so nothing currently puts an
    // embedded server on the runtime classpath for it to find.
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(NspawnmgrApplication.class);
    }

    /**
     * Resolves and tests the database connection *before* Spring Boot's own auto-configured
     * DataSource/JPA/Flyway startup ever runs. If it's reachable, boots normally (unchanged path).
     * If not, registers only a tiny redirect-to-"/" servlet instead of letting Spring's startup
     * fail hard (which would otherwise leave Tomcat serving a bare 404 with no indication why) —
     * ROOT.war (a separate WAR, deployed at Tomcat's root context) is what actually shows the
     * first-boot database setup wizard; see docs/administrator-guide.md &sect;4 and
     * {@code root-wizard}'s own {@code DatabaseSetupWizardServlet} javadoc for the full rationale.
     * Once that wizard finishes, its "Finish setup" button touches this context's own XML, which
     * makes Tomcat redeploy it — re-running this exact method, which then finds the database
     * reachable and boots normally.
     *
     * <p>Skipped entirely under the dev profile (unless {@link #isWizardForced()} says otherwise):
     * {@code DbConnectionSettings} has no visibility into Spring's own profile-specific config (it
     * runs before any Spring context exists), so it can never see application-dev.yml's in-memory
     * H2 override — only the hardcoded MySQL default/env vars/the wizard's own file. Dev's H2 is
     * always instantly available anyway, so there's nothing this check would meaningfully catch
     * there without the override.
     */
    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        if (!isWizardForced() && (isDevProfile() || DbConnectionSettings.resolve().isReachable())) {
            super.onStartup(servletContext);
        } else {
            servletContext.addServlet("db-not-configured", new RedirectToRootServlet()).addMapping("/*");
        }
    }

    /** Mirrors application.yml's own ${SPRING_PROFILES_ACTIVE:dev} resolution (system property,
     *  then env var, defaulting to "dev" — the same default Spring itself would apply). */
    private static boolean isDevProfile() {
        String raw = System.getProperty("SPRING_PROFILES_ACTIVE");
        if (raw == null) {
            raw = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Arrays.asList(raw.split(",")).contains("dev");
    }

    /**
     * Dev-only escape hatch: with dev's H2 always instantly reachable, there is otherwise no way
     * to click through the ROOT.war wizard flow in dev-stack at all. Setting this makes
     * {@link #onStartup} take the "unreachable" branch even under the dev profile.
     */
    private static boolean isWizardForced() {
        String raw = System.getProperty("NSPAWNMGR_FORCE_DB_WIZARD");
        if (raw == null) {
            raw = System.getenv("NSPAWNMGR_FORCE_DB_WIZARD");
        }
        return Boolean.parseBoolean(raw);
    }

    public static void main(String[] args) {
        SpringApplication.run(NspawnmgrApplication.class, args);
    }
}

package com.inviz.visioncerti.security;

import com.inviz.visioncerti.runtime.ProductionReadiness;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start unless the run is either an explicitly enabled development run or a fully configured production
 * run. Registered through {@code META-INF/spring.factories} so it runs before any bean is created.
 *
 * <ul>
 *   <li>{@code prod}: every requirement of {@link ProductionReadiness} must hold (PostgreSQL, OIDC staff
 *       authentication, OpenBao over TLS with separate token files, PC trust anchors with fresh CRLs, no dev or mock
 *       feature). All missing items are reported at once, by variable name only.</li>
 *   <li>{@code dev}: requires {@value #DEV_ENABLE_PROPERTY}=true; synthetic identities and mocks stay dev-only.</li>
 *   <li>Anything else (no profile, unknown profiles, dev + prod) aborts.</li>
 * </ul>
 */
public class FailClosedProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    public static final String DEV_ENABLE_PROPERTY = "visioncerti.dev.synthetic-identities-enabled";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        check(event.getEnvironment());
    }

    static void check(ConfigurableEnvironment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("prod") || active.contains("production")) {
            List<String> problems = ProductionReadiness.problems(environment, Instant.now());
            if (!problems.isEmpty()) {
                throw new IllegalStateException("FAIL_CLOSED: the production profile is not ready: "
                        + String.join("; ", problems));
            }
            return;
        }
        if (!active.contains("dev")) {
            throw new IllegalStateException("FAIL_CLOSED: no runnable profile active (active=" + active + "). "
                    + "Use 'prod' with the complete production configuration, or the explicit 'dev' profile.");
        }
        if (!environment.getProperty(DEV_ENABLE_PROPERTY, Boolean.class, false)) {
            throw new IllegalStateException("FAIL_CLOSED: 'dev' profile active but " + DEV_ENABLE_PROPERTY
                    + " is not true.");
        }
    }
}

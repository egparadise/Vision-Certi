package com.inviz.visioncerti.security;

import java.util.Locale;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Synthetic dev actors (dev headers + {@link DevActorDirectory}) exist only in the {@code dev} profile and only while
 * staff authentication is not OIDC ({@code visioncerti.staff-auth.mode} unset or {@code DEV_HEADERS}).
 */
public final class SyntheticActorsCondition implements Condition {

    public static final String MODE_PROPERTY = "visioncerti.staff-auth.mode";
    public static final String DEV_HEADERS = "DEV_HEADERS";
    public static final String OIDC = "OIDC";

    public static boolean enabled(Environment environment) {
        String mode = environment.getProperty(MODE_PROPERTY, DEV_HEADERS).trim().toUpperCase(Locale.ROOT);
        return environment.acceptsProfiles(Profiles.of("dev")) && !environment.acceptsProfiles(Profiles.of("prod"))
                && (mode.isEmpty() || DEV_HEADERS.equals(mode));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context.getEnvironment());
    }

    /** The complement: no synthetic actors (OIDC staff authentication or any non-dev profile). */
    public static final class Disabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !enabled(context.getEnvironment());
        }
    }
}

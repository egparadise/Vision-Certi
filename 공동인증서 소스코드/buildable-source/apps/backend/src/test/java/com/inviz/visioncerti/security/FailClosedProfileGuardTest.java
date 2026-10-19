package com.inviz.visioncerti.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.VisionCertiApplication;
import com.inviz.visioncerti.signing.MockSigningProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Profile;

/** Non-dev startup must abort before any dev identity or mock signing bean can exist. */
class FailClosedProfileGuardTest {

    private static SpringApplicationBuilder app(String... profiles) {
        return new SpringApplicationBuilder(VisionCertiApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles);
    }

    @Test
    void productionProfileRefusesToStart() {
        assertThatThrownBy(() -> app("prod").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void devCombinedWithProdRefusesToStart() {
        assertThatThrownBy(() -> app("dev", "prod").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void noProfileRefusesToStart() {
        assertThatThrownBy(() -> app().run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void devProfileWithoutExplicitEnableFlagRefusesToStart() {
        assertThatThrownBy(() -> app("dev").run("--" + FailClosedProfileGuard.DEV_ENABLE_PROPERTY + "=false"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAIL_CLOSED");
    }

    @Test
    void devOnlyBeansAreRestrictedToDevProfile() {
        assertThat(DevHeaderAuthFilter.class.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(DevActorDirectory.class.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(MockSigningProvider.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }
}

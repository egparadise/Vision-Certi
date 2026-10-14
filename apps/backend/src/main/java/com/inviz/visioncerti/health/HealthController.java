package com.inviz.visioncerti.health;

import com.inviz.visioncerti.signing.SigningProvider;
import com.inviz.visioncerti.store.Store;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated liveness endpoint; details of the runtime are at {@code /v1/runtime/capabilities}. */
@RestController
@RequestMapping("/v1/health")
public class HealthController {

    public record HealthResponse(
            String status,
            String service,
            List<String> activeProfiles,
            String persistence,
            String signatureMode,
            boolean devSyntheticIdentities,
            boolean clinicalUseAllowed) {
    }

    private final Environment environment;
    private final SigningProvider signingProvider;
    private final Store store;

    public HealthController(Environment environment, SigningProvider signingProvider, Store store) {
        this.environment = environment;
        this.signingProvider = signingProvider;
        this.store = store;
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", "vision-certi-backend", List.of(environment.getActiveProfiles()),
                store.mode(), signingProvider.signatureMode(), environment.matchesProfiles("dev"),
                signingProvider.clinicalUseAllowed());
    }
}

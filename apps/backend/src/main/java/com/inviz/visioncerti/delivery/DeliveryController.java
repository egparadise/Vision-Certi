package com.inviz.visioncerti.delivery;

import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.RequestActor;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public ListResponse<Delivery> list(RequestActor actor) {
        return ListResponse.of(deliveryService.list(actor.tenantId()));
    }

    @GetMapping("/{id}")
    public Delivery get(RequestActor actor, @PathVariable UUID id) {
        return deliveryService.get(actor.tenantId(), id);
    }
}

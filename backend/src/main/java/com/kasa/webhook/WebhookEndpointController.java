package com.kasa.webhook;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/webhook-endpoints")
public class WebhookEndpointController {

    public record CreateEndpointRequest(@NotBlank @Size(max = 2000) String url) {
    }

    private final WebhookEndpointService endpoints;

    public WebhookEndpointController(WebhookEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointService.CreatedEndpoint create(Authentication authentication,
                                                         @Valid @RequestBody CreateEndpointRequest request) {
        return endpoints.create(CurrentTenant.of(authentication), request.url());
    }

    @GetMapping
    public List<WebhookEndpointService.EndpointSummary> list(Authentication authentication) {
        return endpoints.list(CurrentTenant.of(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Authentication authentication, @PathVariable UUID id) {
        endpoints.disable(CurrentTenant.of(authentication), id);
    }

    @GetMapping("/{id}/deliveries")
    public List<WebhookEndpointService.DeliverySummary> deliveries(Authentication authentication,
                                                                   @PathVariable UUID id) {
        return endpoints.deliveries(CurrentTenant.of(authentication), id);
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(Authentication authentication, @PathVariable UUID deliveryId) {
        endpoints.retry(CurrentTenant.of(authentication), deliveryId);
    }
}

package com.incidentops.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.incidentops.catalog.ServiceDtos.*;

@RestController
@RequestMapping("/api/v1")
public class ServiceCatalogController {
    private final ServiceCatalogService service;
    public ServiceCatalogController(ServiceCatalogService service) { this.service = service; }

    @PostMapping("/teams/{teamId}/services")
    public ResponseEntity<ServiceResponse> create(Authentication authentication, @PathVariable String teamId,
        @Valid @RequestBody CreateServiceRequest request) {
        return ResponseEntity.status(201).body(service.create(authentication.getName(), teamId, request));
    }

    @GetMapping("/teams/{teamId}/services")
    public ServicePage list(Authentication authentication, @PathVariable String teamId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(authentication.getName(), teamId, page, size);
    }

    @GetMapping("/services/{serviceId}")
    public ServiceResponse read(Authentication authentication, @PathVariable String serviceId) {
        return service.read(authentication.getName(), serviceId);
    }

    @PutMapping("/services/{serviceId}")
    public ServiceResponse update(Authentication authentication, @PathVariable String serviceId,
        @Valid @RequestBody UpdateServiceRequest request) {
        return service.update(authentication.getName(), serviceId, request);
    }

    @PostMapping("/services/{serviceId}/deactivate")
    public ServiceResponse deactivate(Authentication authentication, @PathVariable String serviceId) {
        return service.deactivate(authentication.getName(), serviceId);
    }
}

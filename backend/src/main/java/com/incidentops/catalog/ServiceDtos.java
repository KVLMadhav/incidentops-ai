package com.incidentops.catalog;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ServiceDtos {
    private ServiceDtos() { }

    public record CreateServiceRequest(
        @NotBlank @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s)\\S(?:.*\\S)", message = "must contain at least two characters and no surrounding whitespace") String name,
        @Size(max = 2000) String description,
        @Size(max = 36) String ownerUserId,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}",
            message = "must use letters, numbers, dot, underscore, colon, slash, or dash") String externalIdentifier) { }

    public record UpdateServiceRequest(
        @NotBlank @Size(min = 2, max = 100)
        @Pattern(regexp = "(?s)\\S(?:.*\\S)", message = "must contain at least two characters and no surrounding whitespace") String name,
        @Size(max = 2000) String description,
        @Size(max = 36) String ownerUserId,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}",
            message = "must use letters, numbers, dot, underscore, colon, slash, or dash") String externalIdentifier) { }

    public record ServiceResponse(String id, String teamId, String name, String description,
        String ownerUserId, String externalIdentifier, boolean active, Instant createdAt, Instant updatedAt) { }

    public record ServicePage(List<ServiceResponse> items, int page, int size,
        long totalElements, int totalPages) { }
}

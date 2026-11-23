package com.incidentops.catalog;

import com.incidentops.entity.Role;
import com.incidentops.exception.ApiException;
import com.incidentops.identity.team.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.incidentops.catalog.ServiceDtos.*;

@Service
public class ServiceCatalogService {
    private final MonitoredServiceRepository services;
    private final TeamMemberRepository members;
    private final TeamService teams;

    public ServiceCatalogService(MonitoredServiceRepository services, TeamMemberRepository members,
                                 TeamService teams) {
        this.services = services;
        this.members = members;
        this.teams = teams;
    }

    @Transactional
    public ServiceResponse create(String userId, String teamId, CreateServiceRequest request) {
        teams.requireRole(userId, teamId, Role.ADMIN, Role.INCIDENT_MANAGER);
        validateOwner(teamId, request.ownerUserId());
        return response(services.saveAndFlush(new MonitoredService(teamId, request.name(),
            request.description(), request.ownerUserId(), request.externalIdentifier())));
    }

    @Transactional(readOnly = true)
    public ServicePage list(String userId, String teamId, int page, int size) {
        teams.requireMembership(userId, teamId);
        var result = services.findAllByTeamId(teamId, PageRequest.of(page, size,
            Sort.by(Sort.Order.asc("nameKey"), Sort.Order.asc("id"))));
        return new ServicePage(result.map(ServiceCatalogService::response).getContent(), page, size,
            result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ServiceResponse read(String userId, String serviceId) {
        return response(permitted(userId, serviceId));
    }

    @Transactional
    public ServiceResponse update(String userId, String serviceId, UpdateServiceRequest request) {
        var service = permitted(userId, serviceId);
        teams.requireRole(userId, service.getTeamId(), Role.ADMIN, Role.INCIDENT_MANAGER);
        validateOwner(service.getTeamId(), request.ownerUserId());
        service.update(request.name(), request.description(), request.ownerUserId(), request.externalIdentifier());
        return response(services.saveAndFlush(service));
    }

    @Transactional
    public ServiceResponse deactivate(String userId, String serviceId) {
        var service = permitted(userId, serviceId);
        teams.requireRole(userId, service.getTeamId(), Role.ADMIN, Role.INCIDENT_MANAGER);
        service.deactivate();
        return response(services.saveAndFlush(service));
    }

    private MonitoredService permitted(String userId, String serviceId) {
        return services.findPermittedById(serviceId, userId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Monitored service was not found"));
    }

    private void validateOwner(String teamId, String ownerUserId) {
        if (ownerUserId != null && !ownerUserId.isBlank()
            && !members.existsByTeamIdAndUserId(teamId, ownerUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OWNER",
                "Service owner must be a member of the same team");
        }
    }

    private static ServiceResponse response(MonitoredService service) {
        return new ServiceResponse(service.getId(), service.getTeamId(), service.getName(),
            service.getDescription(), service.getOwnerUserId(), service.getExternalIdentifier(),
            service.isActive(), service.getCreatedAt(), service.getUpdatedAt());
    }
}

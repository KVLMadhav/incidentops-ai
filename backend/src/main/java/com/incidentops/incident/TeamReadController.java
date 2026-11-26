package com.incidentops.incident;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.List;

@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class TeamReadController {
    private final TeamReadService service;
    public TeamReadController(TeamReadService service){this.service=service;}
    @GetMapping("/summary") public TeamReadService.Summary summary(Authentication auth,@PathVariable String teamId){
        return service.summary(auth.getName(),teamId);
    }
    @GetMapping("/notifications") public List<TeamReadService.Notification> notifications(Authentication auth,@PathVariable String teamId){
        return service.notifications(auth.getName(),teamId);
    }
}

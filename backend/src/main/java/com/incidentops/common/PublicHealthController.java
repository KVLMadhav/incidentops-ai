package com.incidentops.common;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
public class PublicHealthController {
    private final HealthEndpoint health;
    public PublicHealthController(HealthEndpoint health){this.health=health;}
    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String,String>> health(){
        var status=health.health().getStatus();
        return ResponseEntity.status(Status.UP.equals(status)?200:503).body(Map.of("status",status.getCode()));
    }
    @GetMapping("/actuator/health/{probe:readiness|liveness}")
    public ResponseEntity<Map<String,String>> probe(@PathVariable String probe){
        var status=health.healthForPath(probe).getStatus();
        return ResponseEntity.status(Status.UP.equals(status)?200:503).body(Map.of("status",status.getCode()));
    }
}

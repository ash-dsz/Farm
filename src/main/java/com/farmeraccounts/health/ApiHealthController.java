package com.farmeraccounts.health;

import java.util.Map;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiHealthController {

    private final HealthEndpoint healthEndpoint;

    public ApiHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        String status = healthEndpoint.health().getStatus().getCode();
        HttpStatus httpStatus = "UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(Map.of("status", status));
    }
}

package com.incidentops.controller;

import com.incidentops.service.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.incidentops.dto.UserDtos.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return service.current(authentication.getName());
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(service.create(request));
    }

    @GetMapping
    public UserPage list(@RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }
}

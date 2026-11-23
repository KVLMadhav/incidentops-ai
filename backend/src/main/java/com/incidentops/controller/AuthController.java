package com.incidentops.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.incidentops.dto.AuthDtos.*;
import com.incidentops.dto.UserDtos.UserResponse;
import com.incidentops.service.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@SecurityRequirements
@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final UserService users;
    public AuthController(AuthService auth, UserService users) { this.auth = auth; this.users = users; }
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(users.register(request));
    }
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(request));
    }
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.refresh(request));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

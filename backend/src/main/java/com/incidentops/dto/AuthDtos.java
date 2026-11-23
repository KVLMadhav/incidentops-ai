package com.incidentops.dto;
import jakarta.validation.constraints.*;
public final class AuthDtos {
    private AuthDtos() { }
    public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=100) String displayName,
        @NotNull @Pattern(regexp="[\\x20-\\x7E]{12,72}") String password) { }
    public record LoginRequest(@NotBlank @Email @Size(max=254) String email,
        @NotNull @Pattern(regexp="[\\x20-\\x7E]{12,72}") String password) { }
    public record RefreshRequest(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String refreshToken) { }
    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String refreshToken, long refreshExpiresIn) { }
}

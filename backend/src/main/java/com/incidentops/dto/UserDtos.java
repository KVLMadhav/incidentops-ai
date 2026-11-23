package com.incidentops.dto;
import com.incidentops.entity.Role;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class UserDtos {
    private UserDtos() { }
    public record CreateUserRequest(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=100) String displayName,
        @NotNull @Pattern(regexp="[\\x20-\\x7E]{12,72}",
            message="must contain 12–72 printable ASCII characters") String password,
        @NotNull Role role) { }
    public record UserResponse(String id, String email, String displayName, Role role,
                               boolean enabled, Instant createdAt) { }
    public record UserPage(List<UserResponse> items, int page, int size, long totalElements, int totalPages) { }
}

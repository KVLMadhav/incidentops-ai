package com.incidentops.mapper;
import com.incidentops.dto.UserDtos.UserResponse;
import com.incidentops.entity.User;
/** Explicit allowlist: password hashes never cross the HTTP boundary. */
public final class UserMapper {
    private UserMapper() { }
    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
            user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}

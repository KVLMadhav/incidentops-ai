package com.incidentops.service;

import com.incidentops.entity.User;
import com.incidentops.entity.Role;
import com.incidentops.repository.UserRepository;
import com.incidentops.dto.AuthDtos;
import com.incidentops.mapper.UserMapper;

import com.incidentops.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import static com.incidentops.dto.UserDtos.*;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public UserService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserResponse register(AuthDtos.RegisterRequest request) {
        User user = new User(normalizeEmail(request.email()), request.displayName().trim(),
            passwords.encode(request.password()), Role.VIEWER);
        return UserMapper.toResponse(users.saveAndFlush(user));
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        // The database unique constraint, not a race-prone existence check, decides duplicates.
        User user = new User(normalizeEmail(request.email()), request.displayName().trim(),
            passwords.encode(request.password()), request.role());
        return UserMapper.toResponse(users.saveAndFlush(user));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        var result = users.findAll(PageRequest.of(page, size,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new UserPage(result.map(UserMapper::toResponse).getContent(), page, size,
            result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public UserResponse current(String id) {
        return UserMapper.toResponse(users.findById(id).orElseThrow(() ->
            new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required")));
    }
}

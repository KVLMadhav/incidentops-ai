package com.incidentops.config;

import com.incidentops.entity.*;
import com.incidentops.repository.*;
import com.incidentops.service.*;
import com.incidentops.dto.*;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, Validator validator,
        @Value("${app.bootstrap.email}") String email, @Value("${app.bootstrap.password}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // First-run convenience only: never reset/promote an existing account.
        if (users.count() > 0) return;
        var input = new UserDtos.CreateUserRequest(email, "Local Administrator", password, Role.ADMIN);
        if (!validator.validate(input).isEmpty()) {
            throw new IllegalStateException("Bootstrap requires a valid email and a 12–72 character printable ASCII password");
        }
        users.saveAndFlush(new User(UserService.normalizeEmail(email), "Local Administrator",
            passwords.encode(password), Role.ADMIN));
    }
}

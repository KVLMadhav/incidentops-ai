package com.incidentops;

import com.incidentops.config.AdminBootstrap;
import com.incidentops.entity.User;
import com.incidentops.repository.UserRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    @Test void existingDatabaseIsNeverReseededOrPromoted() {
        var users = mock(UserRepository.class);
        var passwords = mock(PasswordEncoder.class);
        when(users.count()).thenReturn(1L);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            new AdminBootstrap(users, passwords, factory.getValidator(), "", "")
                .run(new DefaultApplicationArguments());
        }
        verify(users, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(passwords);
    }

    @Test void blankBootstrapSecretsFailWithoutWritingAnAccount() {
        var users = mock(UserRepository.class);
        var passwords = mock(PasswordEncoder.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var bootstrap = new AdminBootstrap(users, passwords, factory.getValidator(), "admin@example.test", "");
            assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        }
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test void initialAccountUsesNormalizedEmailAndHashedPassword() {
        var users = mock(UserRepository.class);
        var passwords = mock(PasswordEncoder.class);
        when(passwords.encode("Bootstrap-test-123")).thenReturn("hashed-value");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            new AdminBootstrap(users, passwords, factory.getValidator(), "ADMIN@example.test", "Bootstrap-test-123")
                .run(new DefaultApplicationArguments());
        }
        verify(users).saveAndFlush(argThat(user -> user.getEmail().equals("admin@example.test")
            && user.getPasswordHash().equals("hashed-value") && user.getRole().name().equals("ADMIN")));
    }
}

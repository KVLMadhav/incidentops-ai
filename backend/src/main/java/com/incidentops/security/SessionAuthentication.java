package com.incidentops.security;

import com.incidentops.repository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
@Component
public class SessionAuthentication {
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    public SessionAuthentication(UserRepository users,AuthSessionRepository sessions){this.users=users;this.sessions=sessions;}
    public JwtAuthenticationToken authenticate(Jwt token) {
        String sid=token.getClaimAsString("sid");
        if(sid==null||sid.isBlank())throw new InvalidBearerTokenException("Session required");
        sessions.findById(sid).filter(s->s.isActive(Instant.now())&&s.getUserId().equals(token.getSubject()))
            .orElseThrow(()->new InvalidBearerTokenException("Session unavailable"));
        var user=users.findById(token.getSubject()).filter(u->u.isEnabled())
            .orElseThrow(()->new InvalidBearerTokenException("Account unavailable"));
        return new JwtAuthenticationToken(token,List.of(new SimpleGrantedAuthority("ROLE_"+user.getRole().name())),user.getId());
    }
}

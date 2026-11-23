package com.incidentops.repository;
import com.incidentops.entity.RefreshToken;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface RefreshTokenRepository extends JpaRepository<RefreshToken,String> {
    // Fetch token state only AFTER acquiring the session lock.
    @Query("select t.sessionId from RefreshToken t where t.tokenHash = :hash")
    Optional<String> findSessionId(@Param("hash") String hash);
}

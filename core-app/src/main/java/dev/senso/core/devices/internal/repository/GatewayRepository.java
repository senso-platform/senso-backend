package dev.senso.core.devices.internal.repository;

import dev.senso.core.devices.internal.domain.Gateway;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GatewayRepository extends JpaRepository<Gateway, UUID> {

    Optional<Gateway> findByExternalId(String externalId);

    Optional<Gateway> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gateway g where g.id = :id")
    Optional<Gateway> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gateway g where g.externalId = :externalId")
    Optional<Gateway> lockByExternalId(@Param("externalId") String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gateway g where g.id = (select d.gatewayId from Device d where d.id = :deviceId)")
    Optional<Gateway> lockByDeviceId(@Param("deviceId") UUID deviceId);

    @Query(value = """
            SELECT g.* FROM devices.gateways g
            WHERE g.owner_id = :ownerId
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR (g.created_at, g.id) < (CAST(:cursorTime AS timestamptz), CAST(:cursorId AS uuid)))
            ORDER BY g.created_at DESC, g.id DESC
            LIMIT :fetchSize
            """, nativeQuery = true)
    List<Gateway> findPage(
            @Param("ownerId") UUID ownerId,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("fetchSize") int fetchSize);
}

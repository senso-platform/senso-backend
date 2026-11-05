package dev.senso.core.devices.internal.repository;

import dev.senso.core.devices.api.DeviceStatus;
import dev.senso.core.devices.internal.domain.Device;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByGatewayIdAndExternalId(UUID gatewayId, String externalId);

    long countByGatewayIdAndStatus(UUID gatewayId, DeviceStatus status);

    List<Device> findAllByGatewayId(UUID gatewayId);

    @Query(value = """
            SELECT d.* FROM devices.devices d
            JOIN devices.gateways g ON g.id = d.gateway_id
            WHERE g.owner_id = :ownerId
              AND (CAST(:gatewayId AS uuid) IS NULL OR d.gateway_id = CAST(:gatewayId AS uuid))
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR (d.created_at, d.id) < (CAST(:cursorTime AS timestamptz), CAST(:cursorId AS uuid)))
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :fetchSize
            """, nativeQuery = true)
    List<Device> findPage(
            @Param("ownerId") UUID ownerId,
            @Param("gatewayId") UUID gatewayId,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            @Param("fetchSize") int fetchSize);
}

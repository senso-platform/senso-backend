package dev.senso.core.devices.api;

import dev.senso.kernel.page.CursorPage;
import dev.senso.kernel.page.PageRequestParams;
import java.util.Optional;
import java.util.UUID;

/** Public module API. Owner ids must come from a trusted authentication context. */
public interface DeviceQueries {

    /** Worker lookup: only approved, ACTIVE devices can accept telemetry. */
    Optional<DeviceView> resolve(String gatewayExternalId, String deviceExternalId);

    /** A foreign device is indistinguishable from a missing device (404). */
    DeviceView find(UUID ownerId, UUID deviceId);

    /** gatewayId is optional; a supplied foreign gateway produces 404. */
    CursorPage<DeviceView> list(UUID ownerId, UUID gatewayId, PageRequestParams params);
}

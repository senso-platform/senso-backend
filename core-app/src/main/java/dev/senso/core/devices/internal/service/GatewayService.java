package dev.senso.core.devices.internal.service;

import dev.senso.core.devices.api.GatewayView;
import dev.senso.core.devices.internal.domain.Gateway;
import dev.senso.core.devices.internal.mapping.DeviceMapper;
import dev.senso.core.devices.internal.repository.GatewayRepository;
import dev.senso.core.identity.api.IdentityApi;
import dev.senso.kernel.error.NotFoundException;
import dev.senso.kernel.error.ValidationException;
import dev.senso.kernel.id.Ids;
import dev.senso.kernel.page.Cursor;
import dev.senso.kernel.page.CursorPage;
import dev.senso.kernel.page.PageRequestParams;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User-facing operations. Callers must obtain ownerId from the authenticated principal. */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final GatewayRepository gateways;
    private final IdentityApi identity;
    private final DeviceMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public GatewayService(GatewayRepository gateways, IdentityApi identity, DeviceMapper mapper, Clock clock) {
        this.gateways = gateways;
        this.identity = identity;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RegisteredGateway create(UUID ownerId, String name) {
        validateName(name);
        if (ownerId == null || !identity.existsById(ownerId)) {
            throw new NotFoundException("User not found");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id = Ids.newId();
        Gateway gateway =
                gateways.save(new Gateway(id, ownerId, "gw-" + id, name, passwords.encode(password), clock.instant()));
        try (var user = MDC.putCloseable("userId", ownerId.toString());
                var gatewayId = MDC.putCloseable("gatewayId", id.toString())) {
            log.info("Gateway registered");
        }
        return new RegisteredGateway(mapper.toView(gateway), password);
    }

    @Transactional(readOnly = true)
    public GatewayView find(UUID ownerId, UUID gatewayId) {
        return mapper.toView(owned(ownerId, gatewayId));
    }

    @Transactional(readOnly = true)
    public CursorPage<GatewayView> list(UUID ownerId, PageRequestParams params) {
        if (ownerId == null) {
            throw new NotFoundException("User not found");
        }
        Cursor cursor = params.decodeCursor();
        var rows = gateways.findPage(
                ownerId,
                cursor == null ? null : cursor.timestamp(),
                cursor == null ? null : cursor.id(),
                params.fetchSize());
        return CursorPage.of(
                rows.stream().map(mapper::toView).toList(),
                params.limit(),
                row -> new Cursor(row.createdAt(), row.id()));
    }

    @Transactional
    public GatewayView rename(UUID ownerId, UUID gatewayId, String name) {
        if (gatewayId == null) {
            throw new NotFoundException("Gateway not found");
        }
        Gateway gateway = gateways.lockById(gatewayId).orElseThrow(() -> new NotFoundException("Gateway not found"));
        requireOwner(ownerId, gateway);
        validateName(name);
        gateway.rename(name, clock.instant());
        return mapper.toView(gateway);
    }

    private Gateway owned(UUID ownerId, UUID gatewayId) {
        if (ownerId == null || gatewayId == null) {
            throw new NotFoundException("Gateway not found");
        }
        return gateways.findByIdAndOwnerId(gatewayId, ownerId)
                .orElseThrow(() -> new NotFoundException("Gateway not found"));
    }

    private static void requireOwner(UUID ownerId, Gateway gateway) {
        if (!gateway.getOwnerId().equals(ownerId)) {
            throw new NotFoundException("Gateway not found");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ValidationException("Gateway name must contain between 1 and 128 characters");
        }
    }
}

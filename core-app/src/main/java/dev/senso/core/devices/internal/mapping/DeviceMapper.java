package dev.senso.core.devices.internal.mapping;

import dev.senso.core.devices.api.DeviceView;
import dev.senso.core.devices.api.GatewayView;
import dev.senso.core.devices.internal.domain.Device;
import dev.senso.core.devices.internal.domain.Gateway;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DeviceMapper {

    GatewayView toView(Gateway gateway);

    DeviceView toView(Device device);
}

package dev.senso.archrules.compliant.devices.internal.web;

import dev.senso.core.shared.role.ApiRole;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Образец AGENTS.md §3.9: контроллер в internal.web и с @ApiRole. */
@ApiRole
@RestController
@RequestMapping("/api/v1/gateways")
public class GatewayController {}

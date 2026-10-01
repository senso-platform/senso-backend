package dev.senso.archrules.violators.devices.internal.web;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Нарушитель AGENTS.md §3.9: контроллер в правильном пакете, но без @ApiRole. */
@RestController
@RequestMapping("/api/v1/gateways")
public class ControllerWithoutApiRole {}

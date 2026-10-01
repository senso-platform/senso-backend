package dev.senso.archrules.violators.devices.internal.web;

import dev.senso.core.shared.role.ApiRole;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Нарушитель AGENTS.md §4: транзакция на контроллере, а не на методе сервиса. */
@ApiRole
@RestController
public class TransactionalController {

    @Transactional
    @PostMapping("/api/v1/gateways")
    public void create() {}
}

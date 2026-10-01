package dev.senso.archrules.violators.worker;

import org.springframework.scheduling.annotation.Scheduled;

/** Нарушитель AGENTS.md §3.9: планировщик в классе без роли (ни @WorkerRole, ни @ApiRole). */
public class SchedulerOutsideWorker {

    @Scheduled(fixedDelay = 60_000L)
    public void tick() {}
}

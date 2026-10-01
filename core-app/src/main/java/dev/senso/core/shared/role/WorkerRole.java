package dev.senso.core.shared.role;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/** Роль worker: AMQP-листены и планировщики (AGENTS.md §3.9). Профиль {@code local} включает обе роли. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile({"worker", "local"})
public @interface WorkerRole {}

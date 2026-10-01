package dev.senso.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import dev.senso.core.shared.role.ApiRole;
import dev.senso.core.shared.role.WorkerRole;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RestController;

/**
 * Границы модулей и правила из AGENTS.md §3–4. Ослаблять и отключать нельзя (AGENTS.md §8).
 *
 * <p>Каждое правило объявлено вместе с фикстурой-нарушителем ({@link RuleCase}): {@code ArchRuleFixturesTest}
 * проверяет, что правило на ней срабатывает и не бьёт по образцам. Новое правило без фикстуры не добавить.
 */
class ArchitectureTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(CoreApplication.class);

    private static final JavaClasses CORE_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.senso.core");

    /** Правило и простое имя класса-нарушителя из {@code dev.senso.archrules.violators} / {@code archfixture}. */
    record RuleCase(String violatorFixture, ArchRule rule) {}

    @Test
    void moduleBoundariesAndNamedInterfaces() {
        MODULES.verify();
    }

    @Test
    void archunitRulesFromAgentsMd() {
        for (RuleCase ruleCase : rules()) {
            ruleCase.rule().check(CORE_CLASSES);
        }
    }

    // allowEmptyShould(true) — под многие правила кода пока нет; что они не «вечно зелёные», доказывает
    // ArchRuleFixturesTest на фикстурах.
    static List<RuleCase> rules() {
        return List.of(
                // §3.4: JPA-сущности только внутри своего модуля, в internal.domain
                new RuleCase(
                        "EntityWrongPlace",
                        classes()
                                .that()
                                .areAnnotatedWith(Entity.class)
                                .should()
                                .resideInAPackage("..internal.domain..")
                                .because("AGENTS.md §3.4: сущности не покидают свой модуль")
                                .allowEmptyShould(true)),

                // §3.5: у сущности явная схема, и это схема её модуля
                new RuleCase(
                        "EntityWithoutSchema",
                        classes()
                                .that()
                                .areAnnotatedWith(Entity.class)
                                .should(ArchConditions.be(TABLE_IN_OWN_MODULE_SCHEMA))
                                .because("AGENTS.md §3.5: схема БД на модуль, @Table(schema = \"<module>\")")
                                .allowEmptyShould(true)),

                // §3.9: контроллеры — только роль api
                new RuleCase(
                        "ControllerWithoutApiRole",
                        classes()
                                .that()
                                .areAnnotatedWith(RestController.class)
                                .should()
                                .resideInAPackage("..internal.web..")
                                .andShould()
                                .beAnnotatedWith(ApiRole.class)
                                .because("AGENTS.md §3.9: контроллеры только в роли api")
                                .allowEmptyShould(true)),

                // §3.9: у листенера и планировщика есть роль. worker — очереди модулей и фоновые задачи;
                // api — только realtime (анонимная очередь senso.notifications, heartbeat SSE).
                new RuleCase(
                        "ListenerOutsideWorker",
                        methods()
                                .that()
                                .areAnnotatedWith(RabbitListener.class)
                                .should(ArchConditions.be(DECLARED_IN_ROLE_CLASS))
                                .because("AGENTS.md §3.9: AMQP-листенер работает в явной роли")
                                .allowEmptyShould(true)),
                new RuleCase(
                        "SchedulerOutsideWorker",
                        methods()
                                .that()
                                .areAnnotatedWith(Scheduled.class)
                                .should(ArchConditions.be(DECLARED_IN_ROLE_CLASS))
                                .because("AGENTS.md §3.9: планировщик работает в явной роли")
                                .allowEmptyShould(true)),

                // §4: время только через инжектированный Clock
                new RuleCase(
                        "TimeWithoutClock",
                        noClasses()
                                .that()
                                .resideInAPackage("dev.senso.core..")
                                .and()
                                .resideOutsideOfPackage("dev.senso.core.shared..")
                                .should()
                                .callMethod(Instant.class, "now")
                                .orShould()
                                .callMethod(LocalDateTime.class, "now")
                                .orShould()
                                .callMethod(OffsetDateTime.class, "now")
                                .orShould()
                                .callMethod(ZonedDateTime.class, "now")
                                .orShould()
                                .callMethod(LocalDate.class, "now")
                                .orShould()
                                .callMethod(LocalTime.class, "now")
                                .orShould()
                                .callMethod(System.class, "currentTimeMillis")
                                .orShould()
                                .callMethod(Clock.class, "systemUTC")
                                .orShould()
                                .callMethod(Clock.class, "systemDefaultZone")
                                .because("AGENTS.md §4: текущее время брать из инжектированного Clock")),

                // §3.7/§6: в брокер напрямую — только адаптеры internal.messaging (живая телеметрия senso.live).
                // Доменные события наружу — через outbox (@Externalized), а не RabbitTemplate из сервиса.
                new RuleCase(
                        "DomainUsesRabbitTemplate",
                        noClasses()
                                .that()
                                .resideOutsideOfPackages("..internal.messaging..", "dev.senso.core.shared..")
                                .should()
                                .dependOnClassesThat(
                                        assignableTo(AmqpTemplate.class).or(assignableTo(RabbitTemplate.class)))
                                .because("AGENTS.md §3.7: публикация в Rabbit только из internal.messaging или outbox")
                                .allowEmptyShould(true)),

                // §3.6: межмодульные события — records с суффиксом версии
                new RuleCase(
                        "AlertRaised",
                        classes()
                                .that()
                                .resideInAPackage("..api.events..")
                                .should()
                                .beRecords()
                                .andShould(ArchConditions.be(NAME_WITH_VERSION))
                                .because("AGENTS.md §3.6: события наружу — именованные records с версией")
                                .allowEmptyShould(true)),

                // §4: транзакции на сервисах, не на контроллерах
                new RuleCase(
                        "TransactionalController",
                        noMethods()
                                .that()
                                .areDeclaredInClassesThat()
                                .resideInAPackage("..internal.web..")
                                .should(ArchConditions.be(TRANSACTIONAL))
                                .because("AGENTS.md §4: @Transactional на методах сервисов, не контроллеров")
                                .allowEmptyShould(true)),

                // §4: конфигурация через @ConfigurationProperties records, не @Value
                new RuleCase(
                        "ValueInjection",
                        noClasses()
                                .should(ArchConditions.be(USES_VALUE_INJECTION))
                                .because(
                                        "AGENTS.md §4: настройки — @ConfigurationProperties с префиксом senso.<module>")
                                .allowEmptyShould(true)));
    }

    /** Имя модуля — сегмент пакета перед {@code internal}: {@code dev.senso.core.devices.internal.domain}. */
    static String moduleOf(JavaClass javaClass) {
        List<String> segments = Arrays.asList(javaClass.getPackageName().split("\\."));
        int internal = segments.indexOf("internal");
        return internal > 0 ? segments.get(internal - 1) : null;
    }

    private static final DescribedPredicate<JavaClass> TABLE_IN_OWN_MODULE_SCHEMA =
            new DescribedPredicate<>("annotated with @Table(schema = <own module>)") {
                @Override
                public boolean test(JavaClass input) {
                    String module = moduleOf(input);
                    return module != null
                            && input.tryGetAnnotationOfType(Table.class)
                                    .map(table -> module.equals(table.schema()))
                                    .orElse(false);
                }
            };

    private static final DescribedPredicate<JavaMethod> DECLARED_IN_ROLE_CLASS =
            new DescribedPredicate<>("declared in a class annotated with @ApiRole or @WorkerRole") {
                @Override
                public boolean test(JavaMethod input) {
                    JavaClass owner = input.getOwner();
                    return owner.isAnnotatedWith(ApiRole.class) || owner.isAnnotatedWith(WorkerRole.class);
                }
            };

    private static final DescribedPredicate<JavaClass> NAME_WITH_VERSION =
            new DescribedPredicate<>("simple name matching .*V\\d+") {
                @Override
                public boolean test(JavaClass input) {
                    return input.getSimpleName().matches(".*V\\d+");
                }
            };

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final DescribedPredicate<JavaMethod> TRANSACTIONAL =
            new DescribedPredicate<>("transactional (@Transactional on the method or its class)") {
                @Override
                public boolean test(JavaMethod input) {
                    return input.isAnnotatedWith(SPRING_TRANSACTIONAL)
                            || input.isAnnotatedWith(JAKARTA_TRANSACTIONAL)
                            || input.getOwner().isAnnotatedWith(SPRING_TRANSACTIONAL)
                            || input.getOwner().isAnnotatedWith(JAKARTA_TRANSACTIONAL);
                }
            };

    private static final DescribedPredicate<JavaClass> USES_VALUE_INJECTION =
            new DescribedPredicate<>("using @Value on a field, method or parameter") {
                @Override
                public boolean test(JavaClass input) {
                    return input.getFields().stream().anyMatch(field -> field.isAnnotatedWith(Value.class))
                            || input.getCodeUnits().stream()
                                    .anyMatch(unit -> unit.isAnnotatedWith(Value.class)
                                            || unit.getParameters().stream()
                                                    .anyMatch(parameter -> parameter.isAnnotatedWith(Value.class)));
                }
            };

    /** Документация модулей в target/spring-modulith-docs: {@code ./mvnw -pl core-app test -Dmodulith.docs=true}. */
    @Test
    @EnabledIfSystemProperty(named = "modulith.docs", matches = "true")
    void generateModuleDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}

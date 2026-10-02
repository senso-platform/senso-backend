package dev.senso.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import dev.senso.core.ArchitectureTest.RuleCase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Проверка, что правила из {@link ArchitectureTest} не «вечно зелёные». Правила с allowEmptyShould(true) проходят
 * молча, если в шаблоне пакета опечатка или пакет пуст: нарушение никто не заметит. Здесь каждое правило прогоняется
 * на фикстурах — нарушителях (обязано сработать именно на своей фикстуре) и образцах (не обязано сработать).
 *
 * <p>Фикстуры лежат вне dev.senso.core, кроме правил о времени: они привязаны к корню пакета. Так их не увидят
 * component scan, Hibernate-сканирование сущностей и Modulith.
 */
class ArchRuleFixturesTest {

    private static final JavaClasses VIOLATORS = new ClassFileImporter()
            .importPackages("dev.senso.archrules.violators", "dev.senso.core.archfixture.violators");

    private static final JavaClasses SAMPLES = new ClassFileImporter()
            .importPackages("dev.senso.archrules.compliant", "dev.senso.core.archfixture.compliant");

    @Test
    void fixturesAreImported() {
        assertThat(VIOLATORS.size()).as("фикстуры-нарушители импортированы").isGreaterThan(0);
        assertThat(SAMPLES.size()).as("фикстуры-образцы импортированы").isGreaterThan(0);
    }

    @Test
    void everyRuleHasItsOwnExistingViolator() {
        List<String> fixtures =
                ArchitectureTest.rules().stream().map(RuleCase::violatorFixture).toList();
        assertThat(fixtures).as("у каждого правила своя фикстура").doesNotHaveDuplicates();
        for (String fixture : fixtures) {
            assertThat(VIOLATORS.stream().anyMatch(c -> c.getSimpleName().equals(fixture)))
                    .as("фикстура-нарушитель %s не найдена", fixture)
                    .isTrue();
        }
    }

    @Test
    void everyRuleCatchesItsOwnViolator() {
        for (RuleCase ruleCase : ArchitectureTest.rules()) {
            String fixture = ruleCase.violatorFixture();
            EvaluationResult result = ruleCase.rule().evaluate(VIOLATORS);
            List<String> details = result.getFailureReport().getDetails();
            assertThat(result.hasViolation())
                    .as(
                            "правило не сработало на нарушителе %s: %s",
                            fixture, ruleCase.rule().getDescription())
                    .isTrue();
            assertThat(details)
                    .as(
                            "нарушитель %s не в отчёте правила: %s",
                            fixture, ruleCase.rule().getDescription())
                    .anyMatch(detail -> detail.contains(fixture));
        }
    }

    @Test
    void noRuleRejectsCompliantSamples() {
        for (RuleCase ruleCase : ArchitectureTest.rules()) {
            EvaluationResult result = ruleCase.rule().evaluate(SAMPLES);
            assertThat(result.hasViolation())
                    .as(
                            "правило бьёт по корректному коду: %s%n%s",
                            ruleCase.rule().getDescription(),
                            result.getFailureReport().getDetails())
                    .isFalse();
        }
    }
}

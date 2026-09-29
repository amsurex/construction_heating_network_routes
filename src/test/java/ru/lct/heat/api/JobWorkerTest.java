package ru.lct.heat.api;

import org.junit.jupiter.api.Test;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.validation.Diagnostic;
import ru.lct.heat.validation.RuleCheckSummary;
import ru.lct.heat.validation.ValidationReport;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.lct.heat.validation.OutputValidatorTest.variant;

class JobWorkerTest {
    @Test void validationCountsAreAddedToEveryVariantSummary() {
        ValidationReport report = new ValidationReport(false, 2, 1,
                List.of(Diagnostic.error("pipe", "§4", "error"),
                        Diagnostic.warning("pipe", "§2.2", "warning")),
                List.of("limitation"), Map.of("§4", new RuleCheckSummary(3, 2, 0)));
        List<Variant> enriched = JobWorker.withValidationSummary(List.of(variant()), report);
        assertEquals(2L, enriched.get(0).getSummary().getExtra().get("validation_errors"));
        assertEquals(1L, enriched.get(0).getSummary().getExtra().get("validation_warnings"));
    }
}

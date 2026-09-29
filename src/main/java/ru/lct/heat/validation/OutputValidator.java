package ru.lct.heat.validation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.lct.heat.io.OutputGeoJsonReader;
import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;
import ru.lct.heat.routing.SolveOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Независимый валидатор §2–7: не вызывает routing, sizing, cost или внутренний NetworkChecker. */
@Component
@RequiredArgsConstructor
public class OutputValidator {
    private final OutputGeoJsonReader reader;

    public ValidationReport validate(InputModel input, Path output, SolveOptions.Mode mode) throws IOException {
        return validate(input, output, SolveOptions.builder().mode(mode).build());
    }

    public ValidationReport validate(InputModel input, Path output, SolveOptions options) throws IOException {
        List<Variant> variants;
        try { variants = reader.read(output); }
        catch (HeatRoutingException | IllegalArgumentException e) {
            return new ValidationReport(false, 1, 0,
                    List.of(Diagnostic.error(null, "§7", e.getMessage())), limitations(),
                    Map.of("§7", new RuleCheckSummary(1, 1, 0)));
        }
        return validate(input, variants, options);
    }

    public ValidationReport validate(InputModel input, List<Variant> variants, SolveOptions.Mode mode) {
        return validate(input, variants, SolveOptions.builder().mode(mode).build());
    }

    public ValidationReport validate(InputModel input, List<Variant> variants, SolveOptions options) {
        ValidationChecks checks = new ValidationChecks();
        Set<Integer> ranks = new HashSet<>();
        List<Variant> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator.comparingInt(v -> v.getSummary().getRank()));
        double previousScore = Double.NEGATIVE_INFINITY;
        for (Variant variant : sorted) {
            int rank = variant.getSummary().getRank();
            checks.require(rank >= 1 && rank <= variants.size() && ranks.add(rank), variant.getVariantId(),
                    "§6", "Некорректный rank");
            checks.require(variant.getSummary().getScore() >= previousScore, variant.getVariantId(),
                    "§6", "Ранжирование не соответствует score");
            previousScore = variant.getSummary().getScore();
            OutputGraphChecks graph = new OutputGraphChecks(input, variant, checks);
            graph.check();
            new OutputSpatialChecks(input, variant, options, checks, graph).check();
        }
        Map<String, RuleCheckSummary> summary = checks.summary();
        long warnings = summary.values().stream().mapToLong(RuleCheckSummary::getWarnings).sum();
        return new ValidationReport(checks.errors == 0, checks.errors, warnings,
                List.copyOf(checks.diagnostics), limitations(), summary);
    }

    private List<String> limitations() {
        return List.of("§2.5: отсутствие допустимого маршрута нельзя доказать по одному результату",
                "§2.1/§6: пространственная обоснованность и содержательное различие вариантов требуют экспертной оценки",
                "§4: при остром пересечении спецучасток продлевается до выхода из зоны отступа"
                        + " (длиннее формальных 2 м), см. «Границы применения» в docs/solution.md");
    }
}

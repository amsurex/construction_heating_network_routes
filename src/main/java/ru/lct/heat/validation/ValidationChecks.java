package ru.lct.heat.validation;

import java.util.*;

/** Ограничение размера отчёта не скрывает общее число нарушений. */
final class ValidationChecks {
    static final double GEOMETRY_EPS = 0.02;
    final List<Diagnostic> diagnostics = new ArrayList<>();
    final Map<String, MutableRuleSummary> rules = new LinkedHashMap<>();
    long errors;
    void require(boolean condition, Object id, String rule, String message) {
        MutableRuleSummary summary = rules.computeIfAbsent(rule, ignored -> new MutableRuleSummary());
        summary.checks++;
        if (!condition) {
            errors++;
            summary.errors++;
            if (diagnostics.size() < 1000) { diagnostics.add(Diagnostic.error(id, rule, message)); }
        }
    }
    void warn(Object id, String rule, String message) {
        MutableRuleSummary summary = rules.computeIfAbsent(rule, ignored -> new MutableRuleSummary());
        summary.checks++;
        summary.warnings++;
        if (diagnostics.size() < 1000) { diagnostics.add(Diagnostic.warning(id, rule, message)); }
    }
    void close(double actual, double expected, double tolerance, Object id, String rule, String field) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, id, rule,
                field + ": " + actual + ", ожидается " + expected);
    }

    Map<String, RuleCheckSummary> summary() {
        Map<String, RuleCheckSummary> result = new LinkedHashMap<>();
        rules.forEach((rule, value) -> result.put(rule,
                new RuleCheckSummary(value.checks, value.errors, value.warnings)));
        return Collections.unmodifiableMap(result);
    }

    private static final class MutableRuleSummary {
        long checks;
        long errors;
        long warnings;
    }
}

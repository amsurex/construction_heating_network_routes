package ru.lct.heat.validation;

import lombok.Value;
import java.util.Map;
import java.util.List;

@Value
public class ValidationReport {
    boolean valid;
    long errorCount;
    long warningCount;
    List<Diagnostic> diagnostics;
    List<String> limitations;
    /** Число выполненных проверок, ошибок и предупреждений по пунктам ТЗ. */
    Map<String, RuleCheckSummary> ruleSummary;
}

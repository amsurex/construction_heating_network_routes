package ru.lct.heat.validation;

import lombok.Value;

/** Краткая статистика независимой проверки по одному пункту ТЗ. */
@Value
public class RuleCheckSummary {
    long checks;
    long errors;
    long warnings;
}

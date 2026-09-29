package ru.lct.heat.model.ref;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Профиль правил для ресурса (тепло, вода, кабель…): правила по типам ограничений, алиасы типов
 * (school → social_area) и политика для неизвестных типов. Справочник ДУ (таблица 1) общий.
 * Загружается из {@code profiles/<name>.yml} ({@link RuleProfiles}); {@link #heat()} — Техприложение.
 */
@Getter
public final class RuleProfile {

    private final String name;
    private final String description;
    private final Map<String, RestrictionRule> rules;
    private final Map<String, String> aliases;
    private final RestrictionRule unknownTypeRule;

    public RuleProfile(String name, String description, Map<String, RestrictionRule> rules,
                       Map<String, String> aliases, RestrictionRule unknownTypeRule) {
        this.name = name;
        this.description = description;
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        this.unknownTypeRule = unknownTypeRule;
    }

    /** Профиль по Техприложению (таблица 2) — значения из {@link RestrictionRules}. */
    public static RuleProfile heat() {
        return new RuleProfile("heat", "Тепловые сети: Техническое приложение ЛЦТ 2026, таблица 2",
                RestrictionRules.all(), Collections.emptyMap(), RestrictionRules.forType("__unknown__"));
    }

    /** Каноническое имя типа (с учётом алиасов). */
    public String canonical(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toLowerCase();
        return aliases.getOrDefault(t, t);
    }

    public boolean isKnown(String type) {
        return rules.containsKey(canonical(type));
    }

    /** Правило для типа; неизвестный тип → политика профиля (по умолчанию запрет с отступом 1 м). */
    public RestrictionRule forType(String type) {
        RestrictionRule r = rules.get(canonical(type));
        return r != null ? r : unknownTypeRule.toBuilder().restrictionType(canonical(type)).build();
    }
}

package ru.lct.heat.validation;

import lombok.Value;

/** Диагностика с идентификатором исходного объекта и пунктом ТЗ. */
@Value
public class Diagnostic {
    String severity;
    Object objectId;
    String rule;
    String field;
    String message;

    public Diagnostic(String severity, Object objectId, String rule, String field, String message) {
        this.severity = severity;
        this.objectId = objectId;
        this.rule = rule;
        this.field = field;
        this.message = message;
    }

    public Diagnostic(String severity, Object objectId, String rule, String message) {
        this(severity, objectId, rule, null, message);
    }

    public static Diagnostic error(Object id, String rule, String message) {
        return new Diagnostic("ERROR", id, rule, null, message);
    }

    public static Diagnostic error(Object id, String rule, String field, String message) {
        return new Diagnostic("ERROR", id, rule, field, message);
    }

    public static Diagnostic warning(Object id, String rule, String message) {
        return new Diagnostic("WARNING", id, rule, null, message);
    }
}

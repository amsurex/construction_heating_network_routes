package ru.lct.heat.validation;

import lombok.Getter;
import java.util.Collections;
import java.util.List;

@Getter
public class HeatRoutingException extends RuntimeException {
    private final String code;
    private final Object objectId;
    private final List<Diagnostic> diagnostics;

    public HeatRoutingException(String code, Object objectId, String message) {
        this(code, objectId, message, Collections.emptyList());
    }

    public HeatRoutingException(String code, Object objectId, String message, List<Diagnostic> diagnostics) {
        super(message);
        this.code = code;
        this.objectId = objectId;
        this.diagnostics = List.copyOf(diagnostics);
    }
}

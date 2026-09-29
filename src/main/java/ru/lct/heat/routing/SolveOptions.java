package ru.lct.heat.routing;

import lombok.Builder;
import lombok.Value;

/** Параметры запуска расчёта. */
@Value
@Builder
public class SolveOptions {

    public enum Mode {
        /** Обязательный двумерный режим. */
        PLAN_2D,
        /** Дополнительный режим с учётом глубины. */
        DEPTH_3D
    }

    @Builder.Default
    Mode mode = Mode.PLAN_2D;

    /** Сколько содержательно разных вариантов вернуть (1..3). */
    @Builder.Default
    int maxVariants = 3;

    /** Профиль правил ресурса: heat (Техприложение), water, cable, … — файлы profiles/<name>.yml. */
    @Builder.Default
    String resource = "heat";

    /**
     * Режим «эксперт фиксирует»: присоединяться только к этим существующим камерам / участкам (id как строки);
     * пусто — без ограничения.
     */
    @Builder.Default
    java.util.Set<String> pinTieInIds = java.util.Collections.emptySet();

    /** Не присоединяться к этим камерам / участкам (id как строки). */
    @Builder.Default
    java.util.Set<String> excludeTieInIds = java.util.Collections.emptySet();

    /** Игнорировать эти ограничения (id как строки) — «а если бы препятствия не было». */
    @Builder.Default
    java.util.Set<String> excludeRestrictionIds = java.util.Collections.emptySet();

    public static SolveOptions defaults() {
        return SolveOptions.builder().build();
    }
}

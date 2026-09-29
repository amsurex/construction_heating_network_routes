package ru.lct.heat.model.out;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Один вариант новой сети. Все геометрии — EPSG:32637; обратное проецирование и запись GeoJSON
 * выполняет пакет {@code io}.
 */
@Value
@Builder(toBuilder = true)
public class Variant {
    Object variantId;
    /** Краткое человекочитаемое описание, чем вариант отличается (доп. атрибут). */
    String description;
    @Singular
    List<NewPipe> pipes;
    @Singular
    List<NewChamber> chambers;
    @Singular
    List<TechnicalNode> technicalNodes;
    VariantSummary summary;
}

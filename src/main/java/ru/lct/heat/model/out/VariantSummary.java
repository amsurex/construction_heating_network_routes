package ru.lct.heat.model.out;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Сводка по варианту (variant_summary). Все суммы в рублях, длины в метрах. */
@Value
@Builder(toBuilder = true)
public class VariantSummary {
    Object id;
    int rank;
    double constructionCost;
    double chamberConstructionCost;
    int existingChamberTieInCount;
    double existingChamberTieInCost;
    double unconnectedPenalty;
    double calculatedCost;
    double newNetworkLength;
    double score;
    /** Id неподключённых точек — типы значений как во входе. */
    List<Object> unconnectedOksIds;
    /** Прочие доп. properties (разбор стоимости, метрики трассы, объяснение): записываются в GeoJSON как есть. */
    @lombok.Builder.Default
    java.util.Map<String, Object> extra = java.util.Collections.emptyMap();
}

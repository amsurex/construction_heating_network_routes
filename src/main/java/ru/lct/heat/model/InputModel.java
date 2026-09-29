package ru.lct.heat.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Распарсенный и спроецированный в EPSG:32637 входной набор.
 * Формируется пакетом {@code io}, потребляется {@link ru.lct.heat.routing.RoutingEngine}.
 */
@Value
@Builder(toBuilder = true)
public class InputModel {
    @Singular
    List<SourceNode> sources;
    @Singular
    List<ExistingPipe> pipes;
    @Singular
    List<ExistingChamber> chambers;
    @Singular
    List<ConnectionPoint> connectionPoints;
    @Singular
    List<Restriction> restrictions;
}

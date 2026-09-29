package ru.lct.heat.routing;

import ru.lct.heat.model.InputModel;
import ru.lct.heat.model.out.Variant;

import java.util.List;

/**
 * Контракт между обвязкой (api/io) и алгоритмическим ядром.
 * Вход — уже спроецированная модель (EPSG:32637), выход — варианты, отсортированные по rank.
 */
public interface RoutingEngine {

    List<Variant> solve(InputModel input, SolveOptions options);
}

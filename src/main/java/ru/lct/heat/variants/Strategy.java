package ru.lct.heat.variants;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.model.ConnectionPoint;

import ru.lct.heat.model.ref.DiameterSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Стратегия построения варианта: порядок точек, разрешено ли объединение в общие магистрали,
 * какие точки присоединения к существующей сети исключены.
 */
@Value
@Builder(toBuilder = true)
public class Strategy {

    String id;
    String description;
    /** Порядок обработки точек. */
    Comparator<ConnectionPoint> order;
    /** Можно ли присоединяться к уже построенному дереву (иначе — каждая точка отдельно к сети). */
    @Builder.Default
    boolean treeJoins = true;
    /** Фильтр кандидатов присоединения к существующей сети (по координате); null — все. */
    Predicate<Coordinate> tieInFilter;

    /**
     * Максимум мест присоединения к существующей сети (врезок-корней). Каждая новая камера-врезка стоит
     * 3–12 млн (табл. 3.2), поэтому вариант с одним общим вводом часто дешевле, хоть и длиннее;
     * 0 — без ограничения.
     */
    @Builder.Default
    int maxRoots = 0;
    /**
     * Подсказка ДУ по id точки: с каким ДУ прокладывать её трассу (итоговый ДУ её участков из
     * предыдущего прохода — после объединения в магистраль он больше, чем по расходу точки).
     */
    Map<Object, DiameterSpec> dnHints;

    public Strategy withDnHints(Map<Object, DiameterSpec> hints) {
        return toBuilder().dnHints(hints).build();
    }

    public List<ConnectionPoint> sorted(List<ConnectionPoint> points) {
        List<ConnectionPoint> out = new java.util.ArrayList<>(points);
        out.sort(order);
        return out;
    }
}

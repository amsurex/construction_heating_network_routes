package ru.lct.heat.routing;

import lombok.Value;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.SegmentCheck;

import java.util.List;

/** Найденный маршрут: ломаная от старта до цели, стоимость в рублях и проверки по каждому отрезку. */
@Value
public class Path {
    List<Coordinate> coords;
    /** Полная стоимость с учётом штрафов за повороты, спецпроходов и стоимости цели, руб. */
    double cost;
    /** Длина ломаной, м. */
    double length;
    Goal goal;
    /** checks.get(i) относится к отрезку coords[i]→coords[i+1]. */
    List<SegmentCheck> checks;

    public int turns() {
        return Math.max(0, coords.size() - 2);
    }
}

package ru.lct.heat.routing;

import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/** Цель поиска пути: точка + стоимость завершения в ней (врезка/камера), руб. + произвольная ссылка. */
@Value
public class Goal {
    Coordinate point;
    double terminalCost;
    Object ref;
}

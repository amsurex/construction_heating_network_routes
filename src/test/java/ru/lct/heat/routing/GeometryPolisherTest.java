package ru.lct.heat.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heat.geometry.GeomUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ru.lct.heat.support.TestGeoms.c;

class GeometryPolisherTest {

    @Test
    void slidesVertexAlongIncomingDirectionToReachTargetAngle() {
        // a→b по оси X, c правее и выше: поворот 38.7° → хотим 45° → b' = (12, 0)
        Coordinate b2 = GeometryPolisher.slideAlong(c(0, 0), c(10, 0), c(20, 8), 45);
        assertEquals(12.0, b2.x, 1e-6);
        assertEquals(0.0, b2.y, 1e-6);
        assertEquals(45.0, GeomUtil.turnAngleDeg(c(0, 0), b2, c(20, 8)), 1e-6);
    }

    @Test
    void ninetyDegreesIsFootOfPerpendicular() {
        Coordinate b2 = GeometryPolisher.slideAlong(c(0, 0), c(10, 0), c(30, 15), 90);
        assertEquals(30.0, b2.x, 1e-6);
        assertEquals(0.0, b2.y, 1e-6);
    }

    @Test
    void unreachableTargetGivesNull() {
        // c лежит на луче a→b: любой поворот 0°, 45° недостижим
        assertNull(GeometryPolisher.slideAlong(c(0, 0), c(10, 0), c(50, 0), 45));
    }
}

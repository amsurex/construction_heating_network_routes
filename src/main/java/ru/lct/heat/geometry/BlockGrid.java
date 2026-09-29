package ru.lct.heat.geometry;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.util.List;

/**
 * Быстрый растровый предфильтр «отрезок точно заблокирован»: клетки, целиком лежащие внутри зоны
 * отступа запретного препятствия (зона, эродированная на полдиагонали клетки), помечены индексом
 * препятствия. Отрезок, проходящий через помеченную клетку, заблокирован без обращения к JTS.
 * Ответ «не заблокировано» ничего не гарантирует — дальше идёт точная проверка.
 */
public final class BlockGrid {

    private final double cell;
    private final double minX;
    private final double minY;
    private final int cols;
    private final int rows;
    /** Индекс препятствия + 1 (0 — свободно). */
    private final int[] marks;

    public BlockGrid(List<Obstacle> obstacles, double cell) {
        this.cell = cell;
        Envelope env = new Envelope();
        for (Obstacle o : obstacles) {
            if (o.isForbidden()) {
                env.expandToInclude(o.getClearance().getEnvelopeInternal());
            }
        }
        if (env.isNull()) {
            env = new Envelope(0, 0, 0, 0);
        }
        this.minX = env.getMinX();
        this.minY = env.getMinY();
        this.cols = Math.max(1, (int) Math.ceil(env.getWidth() / cell) + 1);
        this.rows = Math.max(1, (int) Math.ceil(env.getHeight() / cell) + 1);
        this.marks = new int[cols * rows];
        double erode = cell * Math.sqrt(2) / 2 + 0.05;
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            if (!o.isForbidden()) {
                continue;
            }
            Geometry eroded = o.getClearance().buffer(-erode);
            if (eroded.isEmpty()) {
                continue;
            }
            PreparedGeometry prep = PreparedGeometryFactory.prepare(eroded);
            Envelope e = eroded.getEnvelopeInternal();
            int c0 = Math.max(0, (int) ((e.getMinX() - minX) / cell));
            int c1 = Math.min(cols - 1, (int) ((e.getMaxX() - minX) / cell));
            int r0 = Math.max(0, (int) ((e.getMinY() - minY) / cell));
            int r1 = Math.min(rows - 1, (int) ((e.getMaxY() - minY) / cell));
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (marks[r * cols + c] != 0) {
                        continue;
                    }
                    Coordinate center = new Coordinate(minX + (c + 0.5) * cell, minY + (r + 0.5) * cell);
                    if (prep.contains(GeomUtil.point(center))) {
                        marks[r * cols + c] = i + 1;
                    }
                }
            }
        }
    }

    /** Индекс препятствия, через зону которого точно проходит отрезок, либо -1. */
    public int blockedBy(Coordinate a, Coordinate b) {
        double len = a.distance(b);
        int n = Math.max(1, (int) Math.ceil(len / (cell * 0.5)));
        for (int k = 0; k <= n; k++) {
            double t = (double) k / n;
            double x = a.x + (b.x - a.x) * t;
            double y = a.y + (b.y - a.y) * t;
            int c = (int) ((x - minX) / cell);
            int r = (int) ((y - minY) / cell);
            if (c < 0 || r < 0 || c >= cols || r >= rows) {
                continue;
            }
            int m = marks[r * cols + c];
            if (m != 0) {
                return m - 1;
            }
        }
        return -1;
    }

    public int cells() {
        return cols * rows;
    }
}

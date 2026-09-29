package ru.lct.heat.model.ref;

/** Константы разделов 2.4, 5, 6 Техприложения. */
public final class CostConstants {

    /** Штраф за неподключённую точку: PENALTY_BASE + PENALTY_PER_TPH * flow_tph. */
    public static final double PENALTY_BASE = 100_000_000;
    public static final double PENALTY_PER_TPH = 500_000;

    /** score = SCORE_COST_WEIGHT * C / SCORE_COST_NORM + SCORE_LENGTH_WEIGHT * L / SCORE_LENGTH_NORM. */
    public static final double SCORE_COST_WEIGHT = 0.7;
    public static final double SCORE_COST_NORM = 25_000_000;
    public static final double SCORE_LENGTH_WEIGHT = 0.3;
    public static final double SCORE_LENGTH_NORM = 100;

    /** Порог для использования существующей камеры, м. */
    public static final double EXISTING_CHAMBER_SNAP_M = 10.0;
    /** Максимум примыканий к камере. */
    public static final int MAX_CHAMBER_CONNECTIONS = 4;
    /** Максимальный угол поворота в одной вершине, градусы. */
    public static final double MAX_TURN_ANGLE_DEG = 90.0;

    /** Режим с глубиной. */
    public static final double DEFAULT_DEPTH_M = 3.0;
    public static final double MIN_DEPTH_M = 0.7;
    public static final double MAX_SLOPE = 0.10;

    private CostConstants() {
    }

    public static double unconnectedPenalty(double flowTph) {
        return PENALTY_BASE + PENALTY_PER_TPH * flowTph;
    }

    public static double score(double calculatedCost, double newNetworkLength) {
        return SCORE_COST_WEIGHT * (calculatedCost / SCORE_COST_NORM)
                + SCORE_LENGTH_WEIGHT * (newNetworkLength / SCORE_LENGTH_NORM);
    }

    /** Kгл по глубине до верха габарита. */
    public static double depthFactor(double depthM) {
        return depthM <= DEFAULT_DEPTH_M ? 1.0 : 1.0 + 0.10 * (depthM - DEFAULT_DEPTH_M);
    }
}

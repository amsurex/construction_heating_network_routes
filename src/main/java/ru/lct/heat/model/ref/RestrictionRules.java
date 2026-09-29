package ru.lct.heat.model.ref;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static ru.lct.heat.model.ref.RestrictionRule.Kind.FORBIDDEN;
import static ru.lct.heat.model.ref.RestrictionRule.Kind.SPECIAL_CROSSING;

/**
 * Таблица 2 Техприложения. Неизвестный restriction_type трактуется как запрет с отступом 1 м
 * (консервативно), см. {@link #forType(String)}.
 */
public final class RestrictionRules {

    public static final String OKS = "oks";
    public static final String PARK = "park";
    public static final String SOCIAL_AREA = "social_area";
    public static final String PROHIBITED_SITE = "prohibited_site";
    public static final String WATER = "water";
    public static final String RAILWAY = "railway";
    public static final String ROAD = "road";
    public static final String TRAM_TRACKS = "tram_tracks";
    public static final String GAS_PIPELINE = "gas_pipeline";
    public static final String POWER_CABLE = "power_cable";
    /** Существующая тепловая сеть при пересечении без врезки (не restriction во входе, но правило есть). */
    public static final String HEAT_NETWORK = "heat_network";

    private static final Map<String, RestrictionRule> RULES;
    private static final RestrictionRule UNKNOWN_DEFAULT = forbidden("unknown");

    static {
        Map<String, RestrictionRule> m = new LinkedHashMap<>();
        put(m, RestrictionRule.builder().restrictionType(OKS).kind(FORBIDDEN).minDistanceM(5.0).kSpecial(1.0).build());
        put(m, forbidden(PARK));
        put(m, forbidden(SOCIAL_AREA));
        put(m, forbidden(PROHIBITED_SITE));
        put(m, forbidden(WATER));
        put(m, forbidden(RAILWAY));
        put(m, RestrictionRule.builder().restrictionType(ROAD).kind(SPECIAL_CROSSING)
                .minDistanceM(1.5).minCrossingAngleDeg(45).specialMarginM(3.0).kSpecial(1.60)
                .verticalClearanceM(1.0).crossUnderOnly(true).build());
        put(m, RestrictionRule.builder().restrictionType(TRAM_TRACKS).kind(SPECIAL_CROSSING)
                .minDistanceM(1.5).minCrossingAngleDeg(45).specialMarginM(3.0).kSpecial(1.75)
                .verticalClearanceM(1.2).crossUnderOnly(true).build());
        put(m, RestrictionRule.builder().restrictionType(GAS_PIPELINE).kind(SPECIAL_CROSSING)
                .minDistanceM(2.0).specialMarginM(2.0).kSpecial(1.25)
                .ownWidthM(0.40).ownHeightM(0.40).ownDepthM(2.8).verticalClearanceM(0.2).build());
        put(m, RestrictionRule.builder().restrictionType(POWER_CABLE).kind(SPECIAL_CROSSING)
                .minDistanceM(2.0).specialMarginM(2.0).kSpecial(1.15)
                .ownWidthM(0.20).ownHeightM(0.20).ownDepthM(2.7).verticalClearanceM(0.5).build());
        put(m, RestrictionRule.builder().restrictionType(HEAT_NETWORK).kind(SPECIAL_CROSSING)
                .minDistanceM(1.0).specialMarginM(2.0).kSpecial(1.05)
                .ownDepthM(3.0).verticalClearanceM(0.5).build());
        RULES = Collections.unmodifiableMap(m);
    }

    private RestrictionRules() {
    }

    private static RestrictionRule forbidden(String type) {
        return RestrictionRule.builder().restrictionType(type).kind(FORBIDDEN).minDistanceM(1.0).kSpecial(1.0).build();
    }

    private static void put(Map<String, RestrictionRule> m, RestrictionRule r) {
        m.put(r.getRestrictionType(), r);
    }

    public static Map<String, RestrictionRule> all() {
        return RULES;
    }

    /** Правило для типа; неизвестный тип → запрет с отступом 1 м. */
    public static RestrictionRule forType(String restrictionType) {
        RestrictionRule r = RULES.get(restrictionType);
        return r != null ? r : UNKNOWN_DEFAULT;
    }

    public static boolean isKnown(String restrictionType) {
        return RULES.containsKey(restrictionType);
    }
}

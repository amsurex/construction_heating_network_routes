package ru.lct.heat.model.ref;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Загрузка профилей правил из {@code classpath:profiles/<name>.yml}. Формат:
 * <pre>
 * name: heat
 * description: ...
 * unknown_type: { rule: forbidden, min_distance_m: 1.0 }
 * aliases: { school: social_area }
 * rules:
 *   - type: road
 *     rule: special            # forbidden | special
 *     min_distance_m: 1.5
 *     min_crossing_angle_deg: 45
 *     special_margin_m: 3.0
 *     k_special: 1.6
 *     own_width_m / own_height_m / own_depth_m / vertical_clearance_m / cross_under_only
 * </pre>
 */
public final class RuleProfiles {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, RuleProfile> CACHE = new ConcurrentHashMap<>();

    private RuleProfiles() {
    }

    /** Профиль по имени; {@code heat} без файла — из {@link RestrictionRules}. Неизвестное имя — исключение. */
    public static RuleProfile load(String name) {
        String key = name == null || name.isBlank() ? "heat" : name.trim().toLowerCase();
        return CACHE.computeIfAbsent(key, RuleProfiles::read);
    }

    private static RuleProfile read(String name) {
        String path = "/profiles/" + name + ".yml";
        try (InputStream in = RuleProfiles.class.getResourceAsStream(path)) {
            if (in == null) {
                if ("heat".equals(name)) {
                    return RuleProfile.heat();
                }
                throw new IllegalArgumentException("Неизвестный профиль ресурса: " + name);
            }
            ProfileDto dto = YAML.readValue(in, ProfileDto.class);
            Map<String, RestrictionRule> rules = new LinkedHashMap<>();
            for (RuleDto r : dto.rules) {
                rules.put(r.type, toRule(r, r.type));
            }
            RuleDto unknown = dto.unknownType != null ? dto.unknownType : new RuleDto();
            if (unknown.rule == null) {
                unknown.rule = "forbidden";
                unknown.minDistanceM = 1.0;
            }
            Map<String, String> aliases = dto.aliases != null ? dto.aliases : new LinkedHashMap<>();
            return new RuleProfile(dto.name != null ? dto.name : name, dto.description, rules, aliases,
                    toRule(unknown, "unknown"));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать профиль " + path, e);
        }
    }

    private static RestrictionRule toRule(RuleDto r, String type) {
        boolean forbidden = !"special".equalsIgnoreCase(r.rule);
        return RestrictionRule.builder()
                .restrictionType(type)
                .kind(forbidden ? RestrictionRule.Kind.FORBIDDEN : RestrictionRule.Kind.SPECIAL_CROSSING)
                .minDistanceM(r.minDistanceM)
                .minCrossingAngleDeg(r.minCrossingAngleDeg)
                .specialMarginM(r.specialMarginM)
                .kSpecial(forbidden ? 1.0 : (r.kSpecial > 0 ? r.kSpecial : 1.0))
                .ownWidthM(r.ownWidthM)
                .ownHeightM(r.ownHeightM)
                .ownDepthM(r.ownDepthM)
                .verticalClearanceM(r.verticalClearanceM)
                .crossUnderOnly(r.crossUnderOnly)
                .build();
    }

    @Data
    static class ProfileDto {
        String name;
        String description;
        RuleDto unknownType;
        Map<String, String> aliases;
        List<RuleDto> rules = new ArrayList<>();

        public void setUnknown_type(RuleDto r) {
            this.unknownType = r;
        }
    }

    @Data
    static class RuleDto {
        String type;
        String rule;
        double minDistanceM;
        double minCrossingAngleDeg;
        double specialMarginM;
        double kSpecial;
        double ownWidthM;
        double ownHeightM;
        double ownDepthM;
        double verticalClearanceM;
        boolean crossUnderOnly;
        boolean extension;
        String note;

        public void setMin_distance_m(double v) {
            minDistanceM = v;
        }

        public void setMin_crossing_angle_deg(double v) {
            minCrossingAngleDeg = v;
        }

        public void setSpecial_margin_m(double v) {
            specialMarginM = v;
        }

        public void setK_special(double v) {
            kSpecial = v;
        }

        public void setOwn_width_m(double v) {
            ownWidthM = v;
        }

        public void setOwn_height_m(double v) {
            ownHeightM = v;
        }

        public void setOwn_depth_m(double v) {
            ownDepthM = v;
        }

        public void setVertical_clearance_m(double v) {
            verticalClearanceM = v;
        }

        public void setCross_under_only(boolean v) {
            crossUnderOnly = v;
        }
    }
}

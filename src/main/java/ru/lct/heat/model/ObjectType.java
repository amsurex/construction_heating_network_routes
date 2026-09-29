package ru.lct.heat.model;

/** Значения object_type во входном и выходном GeoJSON. */
public enum ObjectType {
    SOURCE("source"),
    HEAT_NETWORK("heat_network"),
    HEAT_CHAMBER("heat_chamber"),
    OKS_CONNECTION_POINT("oks_connection_point"),
    RESTRICTION("restriction"),
    TECHNICAL_NODE("technical_node"),
    VARIANT_SUMMARY("variant_summary");

    private final String code;

    ObjectType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ObjectType fromCode(String code) {
        for (ObjectType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}

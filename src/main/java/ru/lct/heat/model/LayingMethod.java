package ru.lct.heat.model;

/** Способ прокладки участка новой сети. */
public enum LayingMethod {
    BASE("base"),
    SPECIAL("special");

    private final String code;

    LayingMethod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

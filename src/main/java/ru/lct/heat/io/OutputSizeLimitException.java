package ru.lct.heat.io;

import java.io.IOException;

/** Выходной GeoJSON превысил настроенный предел размера. */
public class OutputSizeLimitException extends IOException {
    public OutputSizeLimitException(long maxBytes) {
        super("Результат превышает максимально допустимый размер " + maxBytes + " байт");
    }
}

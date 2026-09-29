package ru.lct.heat.api;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import ru.lct.heat.validation.*;
import java.util.*;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @Value
    public static class ErrorBody {
        String code;
        String message;
        Object objectId;
        List<Diagnostic> diagnostics;
    }

    @ExceptionHandler(HeatRoutingException.class)
    public ResponseEntity<ErrorBody> routing(HeatRoutingException e) {
        int status;
        switch (e.getCode()) {
            case "NOT_FOUND": status = 404; break;
            case "NOT_READY":
            case "NOT_CANCELLABLE": status = 409; break;
            case "BUSY": status = 503; break;
            case "BAD_REQUEST": status = 400; break;
            default: status = 422;
        }
        return ResponseEntity.status(status).body(new ErrorBody(e.getCode(), e.getMessage(),
                e.getObjectId(), e.getDiagnostics()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> size(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(new ErrorBody("FILE_TOO_LARGE", "Максимальный размер файла — 3 ГБ",
                null, List.of()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("BAD_REQUEST", "Проверьте файл и параметры запроса",
                null, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Ошибка API", e);
        return ResponseEntity.status(500).body(new ErrorBody("INTERNAL_ERROR", "Внутренняя ошибка сервиса",
                null, List.of()));
    }
}

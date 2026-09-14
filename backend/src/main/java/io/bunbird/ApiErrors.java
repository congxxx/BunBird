package io.bunbird;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> invalid(MethodArgumentNotValidException exception) {
        // The default resolver logs rejected values, which may contain private message content.
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "fields",
                exception.getBindingResult().getFieldErrors().stream().map(e -> e.getField()).distinct().toList()));
    }
}

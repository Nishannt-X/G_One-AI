package com.example.g_one;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** One JSON error shape for the UI: {code, message}. Internals are logged, never returned. */
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooBig() {
        return ResponseEntity.status(413).body(Map.of("code", "too_long", "message", "Recording is too large."));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, String>> noAuth() {
        return ResponseEntity.status(401).body(Map.of("code", "unauthorized", "message", "Join the room first."));
    }
}

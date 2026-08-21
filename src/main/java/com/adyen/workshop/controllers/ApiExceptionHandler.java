package com.adyen.workshop.controllers;

import com.adyen.service.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Both READMEs deliberately require attempting invalid sequences (charging a cancelled token,
 * refunding before capture, etc.) to observe Adyen's real rejection. Without this handler, that
 * rejection surfaces as an uncaught ApiException, which Spring turns into a raw stack-trace 500
 * instead of the plain-text 4xx the frontend's callEndpoint() already renders as "Failed: ...".
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApiException(ApiException e) {
        log.warn("Adyen API rejected the request: {}", e.getMessage());

        var error = e.getError();
        String message = (error != null && error.getMessage() != null) ? error.getMessage() : e.getMessage();

        HttpStatus status = HttpStatus.resolve(e.getStatusCode());
        return ResponseEntity.status(status != null ? status : HttpStatus.UNPROCESSABLE_ENTITY).body(message);
    }
}

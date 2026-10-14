package com.inviz.visioncerti.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest request) {
        return body(e.status(), e.code(), e.getMessage(), e.retryable(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", detail, false, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception e, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body or parameter could not be parsed",
                false, request);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> missing(Exception e, HttpServletRequest request) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", e.getMessage(), false, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return body(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Uploaded file exceeds the size limit",
                false, request);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorResponse> multipart(MultipartException e, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Multipart request could not be parsed", false,
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Content-Type", false,
                request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not allowed", false, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noRoute(NoResourceFoundException e, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error correlationId={}", CorrelationIdFilter.current(request), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", true, request);
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message,
            boolean retryable, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, CorrelationIdFilter.current(request), retryable));
    }
}

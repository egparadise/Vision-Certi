package com.inviz.visioncerti.common;

/** Uniform error body: {@code {code,message,correlationId,retryable}}. */
public record ErrorResponse(String code, String message, String correlationId, boolean retryable) {
}

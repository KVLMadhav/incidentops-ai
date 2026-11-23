package com.incidentops.exception;

import java.util.List;

public record ApiError(String code, String message, String correlationId, String path,
                       List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) { }
}

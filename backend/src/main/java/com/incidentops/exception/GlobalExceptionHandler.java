package com.incidentops.exception;

import com.incidentops.common.CorrelationIdFilter;
import com.incidentops.exception.ApiError.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiError> response(int status, String code, String message,
                                               HttpServletRequest request, List<FieldError> fields) {
        return ResponseEntity.status(status).body(new ApiError(code, message,
            CorrelationIdFilter.from(request), request.getRequestURI(), fields));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> application(ApiException ex, HttpServletRequest request) {
        return response(ex.status().value(), ex.code(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> validation(Exception ex, HttpServletRequest request) {
        // Do not echo rejected values: auth requests may contain passwords or tokens.
        List<FieldError> fields = ex instanceof MethodArgumentNotValidException invalid
            ? invalid.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .distinct().toList()
            : ex instanceof HandlerMethodValidationException invalid
                ? invalid.getParameterValidationResults().stream().flatMap(result ->
                    result.getResolvableErrors().stream().map(error -> new FieldError(
                        result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                    .distinct().toList()
                : List.of();
        return response(400, "INVALID_REQUEST", "Request fields or parameters are invalid", request, fields);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(409, "CONFLICT", "Request conflicts with an existing record", request, List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return response(409, "STALE_VERSION", "Another person changed this incident. Reload it and try again.", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "Insufficient permissions", request, List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException ex, HttpServletRequest request) {
        log.error("database_request_failed type={}", ex.getClass().getSimpleName());
        return response(503, "SERVICE_UNAVAILABLE", "Service temporarily unavailable", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected_request_failure correlationId={} type={}",
            CorrelationIdFilter.from(request), ex.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "An unexpected error occurred", request, List.of());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(Exception ex,HttpServletRequest request){
        return response(405,"METHOD_NOT_ALLOWED","This action is not supported",request,List.of());
    }
}

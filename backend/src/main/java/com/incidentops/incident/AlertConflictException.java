package com.incidentops.incident;

import com.incidentops.exception.ApiException;
import org.springframework.http.HttpStatus;

/** This specific rejection commits the conflict audit, never a change to the original alert. */
public class AlertConflictException extends ApiException {
    public AlertConflictException() {
        super(HttpStatus.CONFLICT,"ALERT_IDENTITY_CONFLICT","This alert identity already has different immutable fields");
    }
}

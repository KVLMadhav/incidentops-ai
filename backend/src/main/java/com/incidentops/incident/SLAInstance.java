package com.incidentops.incident;

import java.time.Instant;
import java.util.List;

/** One lifetime SLA per incident; reopening preserves its original evidence. */
public record SLAInstance(String id, String incidentId, String policyId, String policyVersion,
                          Severity severity, Instant startedAt, String correlationId,
                          List<SlaService.Target> targets) {}

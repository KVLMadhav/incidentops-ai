package com.incidentops.incident;

import java.time.Instant;

/** Immutable policy version. Durations are elapsed seconds; SEV1 is most urgent. */
public record SLAPolicy(String id, String teamId, String name, Severity severity,
                        long acknowledgementSeconds, long resolutionSeconds,
                        boolean active, long version, Instant effectiveAt) {}

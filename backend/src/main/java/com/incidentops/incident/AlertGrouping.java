package com.incidentops.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Opt in with metadata.alertType and nonempty metadata.stableLabels (string values only). */
public final class AlertGrouping {
    private AlertGrouping() {}
    public static String fingerprint(String service,JsonNode metadata) {
        if(metadata==null)return null;
        var type=metadata.path("alertType");var labels=metadata.path("stableLabels");
        if(!type.isTextual()||type.asText().isBlank()||!labels.isObject()||labels.isEmpty())return null;
        Map<String,String> stable=new TreeMap<>();
        var fields=labels.fields();
        while(fields.hasNext()) {
            var f=fields.next();
            if(f.getKey().isBlank()||!f.getValue().isTextual()||f.getValue().asText().isBlank())return null;
            stable.put(f.getKey(),f.getValue().asText());
        }
        try {
            String canonical=new ObjectMapper().writeValueAsString(List.of(service,type.asText().trim().toLowerCase(Locale.ROOT),stable));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}

package com.incidentops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentops.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Used inside the command transaction, after locking the incident and checking current permissions. */
@Component
public class CommandReceipts {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    public CommandReceipts(JdbcTemplate jdbc,ObjectMapper json,Clock clock){this.jdbc=jdbc;this.json=json;this.clock=clock;}

    public <T> T replay(String incident,String command,String actor,String action,Object request,Class<T> type) {
        if(command==null) return null; // Compatibility with Day 2 clients; these calls are not retry-deduplicated.
        validate(command);
        var rows=jdbc.queryForList("SELECT * FROM incident_command_receipts WHERE incident_id=? AND command_id=?",incident,command);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        if(!actor.equals(row.get("actor_user_id"))||!hash(action,request).equals(row.get("request_hash")))
            throw new ApiException(HttpStatus.CONFLICT,"COMMAND_ID_REUSED","Use a new commandId for a different request");
        try { return json.readValue((String)row.get("response_json"),type); }
        catch(Exception e){throw new IllegalStateException("Invalid command receipt",e);}
    }
    public <T> T store(String incident,String command,String actor,String action,Object request,T response){
        if(command!=null) try {
            jdbc.update("""
                INSERT INTO incident_command_receipts(incident_id,command_id,actor_user_id,request_hash,response_json,created_at)
                VALUES (?,?,?,?,?,?)
                """,incident,command,actor,hash(action,request),json.writeValueAsString(response),Timestamp.from(clock.instant()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return response;
    }
    private static void validate(String id){
        try { if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e){throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_COMMAND_ID","commandId must be a canonical UUID");}
    }
    private String hash(String action,Object request){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+":"+json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}

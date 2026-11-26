package com.incidentops.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.events.enabled",havingValue="true")
public class NotificationListener {
    private final NotificationService service;
    private final ObjectMapper json;
    public NotificationListener(NotificationService service,ObjectMapper json){this.service=service;this.json=json;}
    @KafkaListener(topics="${app.events.topic}",groupId="incidentops-notifications-v1")
    public void receive(String value,Acknowledgment ack) throws Exception {
        var event=json.readValue(value,IncidentEvent.class);
        try(var context=org.slf4j.MDC.putCloseable("correlationId",event.correlationId())){
            service.process(event);
            ack.acknowledge(); // process is a separate transactional bean: its commit has already finished.
        }
    }
}

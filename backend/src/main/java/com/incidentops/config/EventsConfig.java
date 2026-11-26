package com.incidentops.config;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name="app.events.enabled",havingValue="true")
public class EventsConfig {
    @Bean public NewTopic incidentTopic(@Value("${app.events.topic}")String topic){
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
    @Bean public NewTopic incidentDeadLetterTopic(@Value("${app.events.topic}")String topic){
        return TopicBuilder.name(topic+".DLT").partitions(3).replicas(1).build();
    }
    @Bean public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String,String> kafka,
                                                       @Value("${app.events.topic}")String topic,
                                                       io.micrometer.core.instrument.MeterRegistry metrics){
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new TopicPartition(topic+".DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(8));
        var handler=new DefaultErrorHandler((record,error)->{
            recoverer.accept(record,error);
            metrics.counter("incidentops.consumer.dlq").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("topic",record.topic())
                .addKeyValue("partition",record.partition()).addKeyValue("offset",record.offset()).log("Consumer sent record to DLT");
        },new FixedBackOff(1000L,2L));
        // Parsing, schema and business failures all receive three bounded attempts.
        handler.setClassifications(java.util.Map.of(Exception.class,true),true);
        handler.setRetryListeners((record,error,attempt)->{
            metrics.counter("incidentops.consumer.failures").increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("topic",record.topic())
                .addKeyValue("partition",record.partition()).addKeyValue("offset",record.offset())
                .addKeyValue("attempt",attempt).addKeyValue("errorType",error.getClass().getSimpleName()).log("Consumer processing failed");
        });
        handler.setCommitRecovered(true);
        return handler;
    }
    @Bean public org.springframework.transaction.support.TransactionTemplate transactionTemplate(
            org.springframework.transaction.PlatformTransactionManager manager){
        return new org.springframework.transaction.support.TransactionTemplate(manager);
    }
}

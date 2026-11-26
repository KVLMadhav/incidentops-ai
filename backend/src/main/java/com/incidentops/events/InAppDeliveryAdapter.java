package com.incidentops.events;

import org.springframework.stereotype.Component;

/** Local inbox delivery only. This adapter has no email, Slack, or network client. */
@Component
public class InAppDeliveryAdapter {
    public void deliver(String deliveryId,String channel,String target){
        if(!"IN_APP".equals(channel))throw new IllegalArgumentException("Unsupported delivery channel");
    }
}

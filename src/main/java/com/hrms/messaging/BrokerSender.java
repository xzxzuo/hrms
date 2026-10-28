package com.hrms.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class BrokerSender {
  private final RabbitTemplate rabbit;

  public BrokerSender(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  public void send(String route, Message message) {
    try {
      var correlation = new CorrelationData(java.util.UUID.randomUUID().toString());
      rabbit.send(BrokerConfig.EXCHANGE, route, message, correlation);
      var confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
      if (!confirm.isAck() || correlation.getReturned() != null)
        throw new IllegalStateException("Broker rejected message");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Broker confirmation unavailable", e);
    }
  }

  public Message event(String id, String type, String aggregate) {
    return MessageBuilder.withBody(new byte[0])
        .setMessageId(id)
        .setHeader("eventType", type)
        .setHeader("aggregateId", aggregate)
        .setHeader("attempt", 0)
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
        .build();
  }
}

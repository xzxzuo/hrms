package com.hrms.messaging;

import com.hrms.shared.Db;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Events {
  private final Db db;

  public Events(Db db) {
    this.db = db;
  }

  public String add(String type, Object aggregate) {
    String id = UUID.randomUUID().toString();
    db.exec(
        "INSERT INTO outbox_events(id,event_type,aggregate_id) VALUES(?,?,?)",
        id,
        type,
        aggregate.toString());
    return id;
  }
}

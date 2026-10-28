package com.hrms.messaging;

import com.hrms.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MessageProcessor {
  private final Db db;
  private final Tx tx;
  private final List<DomainEventHandler> handlers;

  public MessageProcessor(Db db, Tx tx, List<DomainEventHandler> handlers) {
    this.db = db;
    this.tx = tx;
    this.handlers = handlers;
  }

  public void process(String id, String type, String aggregate) {
    tx.run(
        () -> {
          if (db.exec("INSERT IGNORE INTO processed_messages(message_id) VALUES(?)", id) == 0)
            return null;
          var handler =
              handlers.stream()
                  .filter(h -> h.supports(type))
                  .findFirst()
                  .orElseThrow(() -> new Problem(400, "UNKNOWN_EVENT"));
          handler.handle(id, type, aggregate);
          return null;
        });
  }
}

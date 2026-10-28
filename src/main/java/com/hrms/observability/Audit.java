package com.hrms.observability;

import com.hrms.shared.Db;
import org.springframework.stereotype.Component;

@Component
public class Audit {
  private final Db db;

  public Audit(Db db) {
    this.db = db;
  }

  public void log(long actor, String action, String entity) {
    db.exec(
        "INSERT INTO audit_logs(actor_id,action,entity_ref) VALUES(?,?,?)", actor, action, entity);
  }
}

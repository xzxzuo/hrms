package com.hrms.observability;

import com.hrms.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Reconciliation {
  private final Db db;
  private final boolean jobs;
  private final AtomicInteger stock = new AtomicInteger(),
      quota = new AtomicInteger(),
      outbox = new AtomicInteger();

  public Reconciliation(
      Db db, MeterRegistry metrics, @Value("${hrms.jobs-enabled:true}") boolean jobs) {
    this.db = db;
    this.jobs = jobs;
    metrics.gauge("hrms.inventory.mismatches", stock);
    metrics.gauge("hrms.campaign.mismatches", quota);
    metrics.gauge("hrms.outbox.backlog", outbox);
  }

  public Map<String, Object> check() {
    var inventories =
        db.list(
            "SELECT i.*,COALESCE(n.expected_held,0) AS expected_held,COALESCE(n.expected_sold,0) AS expected_sold FROM room_daily_inventory i LEFT JOIN (SELECT bn.room_type_id,bn.stay_date,SUM(IF(b.status='PENDING_PAYMENT',bn.quantity,0)) AS expected_held,SUM(IF(b.status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT'),bn.quantity,0)) AS expected_sold FROM booking_nights bn JOIN bookings b ON b.id=bn.booking_id GROUP BY bn.room_type_id,bn.stay_date) n ON n.room_type_id=i.room_type_id AND n.stay_date=i.stay_date WHERE i.held<>COALESCE(n.expected_held,0) OR i.sold<>COALESCE(n.expected_sold,0) OR i.total<>i.available+i.held+i.sold+i.blocked LIMIT 100");
    var campaigns =
        db.list(
            "SELECT c.id,c.quota,c.quota_used,COUNT(r.id) AS successful FROM seckill_campaigns c LEFT JOIN seckill_requests r ON r.campaign_id=c.id AND r.status='SUCCESS' GROUP BY c.id,c.quota,c.quota_used HAVING c.quota_used<>COUNT(r.id) OR COUNT(r.id)>c.quota LIMIT 100");
    stock.set(inventories.size());
    quota.set(campaigns.size());
    outbox.set((int) db.count("SELECT COUNT(*) FROM outbox_events WHERE status='NEW'"));
    return Map.of(
        "inventoryMismatches", inventories, "campaignMismatches", campaigns, "limit", 100);
  }

  @Scheduled(fixedDelay = 60000)
  public void scheduled() {
    if (jobs) {
      check();
      if (stock.get() + quota.get() > 0)
        org.slf4j.LoggerFactory.getLogger(getClass())
            .error("Reconciliation mismatch inventory={} campaigns={}", stock.get(), quota.get());
    }
  }
}

package com.hrms.admin;

import com.hrms.identity.Actor;
import com.hrms.messaging.BrokerConfig;
import com.hrms.observability.*;
import com.hrms.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final Db db;
  private final Tx tx;
  private final Actor actor;
  private final Audit audit;
  private final Reconciliation reconciliation;
  private final RabbitAdmin broker;

  public AdminController(
      Db db,
      Tx tx,
      Actor actor,
      Audit audit,
      Reconciliation reconciliation,
      ConnectionFactory factory) {
    this.db = db;
    this.tx = tx;
    this.actor = actor;
    this.audit = audit;
    this.reconciliation = reconciliation;
    this.broker = new RabbitAdmin(factory);
  }

  @GetMapping("/hotels/{hotel}/bookings")
  public List<Map<String, Object>> orders(@PathVariable long hotel) {
    actor.hotel(hotel);
    return db.list("SELECT * FROM bookings WHERE hotel_id=? ORDER BY id DESC LIMIT 100", hotel);
  }

  @GetMapping("/hotels/{hotel}/report")
  public Map<String, Object> report(@PathVariable long hotel, @RequestParam LocalDate date) {
    actor.hotel(hotel);
    var orders =
        db.one(
            "SELECT COUNT(*) AS ordersCreated,COALESCE(SUM(status IN ('CANCELLED','EXPIRED')),0) AS cancelled FROM bookings WHERE hotel_id=? AND created_at>=? AND created_at<?",
            hotel,
            date,
            date.plusDays(1));
    var inventory =
        db.one(
            "SELECT COALESCE(SUM(i.total-i.blocked),0) AS sellable,COALESCE(SUM(i.sold),0) AS sold,COALESCE(SUM(i.held),0) AS held FROM room_daily_inventory i JOIN room_types r ON r.id=i.room_type_id WHERE r.hotel_id=? AND i.stay_date=?",
            hotel,
            date);
    BigDecimal charged =
        Db.money(
            db.one(
                "SELECT COALESCE(SUM(p.amount),0) AS amount FROM payments p JOIN bookings b ON b.id=p.booking_id WHERE b.hotel_id=? AND p.status='SUCCEEDED' AND p.created_at>=? AND p.created_at<?",
                hotel,
                date,
                date.plusDays(1)),
            "amount");
    BigDecimal refunded =
        Db.money(
            db.one(
                "SELECT COALESCE(SUM(r.amount),0) AS amount FROM refunds r JOIN bookings b ON b.id=r.booking_id WHERE b.hotel_id=? AND r.status='COMPLETED' AND r.created_at>=? AND r.created_at<?",
                hotel,
                date,
                date.plusDays(1)),
            "amount");
    var result = new LinkedHashMap<String, Object>();
    result.put("date", date);
    result.put("accountingTimezone", "UTC");
    result.putAll(orders);
    result.putAll(inventory);
    result.put(
        "reservationOccupancyPct",
        ratio(Db.money(inventory, "sold"), Db.money(inventory, "sellable")));
    result.put(
        "cancellationPct", ratio(Db.money(orders, "cancelled"), Db.money(orders, "ordersCreated")));
    result.put("charged", charged);
    result.put("refunded", refunded);
    result.put("netRevenue", charged.subtract(refunded));
    result.put(
        "physicalRoomsOccupiedNow",
        db.count(
            "SELECT COUNT(*) FROM physical_rooms p JOIN room_types r ON r.id=p.room_type_id WHERE r.hotel_id=? AND p.status='OCCUPIED'",
            hotel));
    return result;
  }

  private BigDecimal ratio(BigDecimal n, BigDecimal d) {
    return d.signum() == 0
        ? BigDecimal.ZERO
        : n.multiply(BigDecimal.valueOf(100)).divide(d, 2, RoundingMode.HALF_UP);
  }

  @GetMapping("/ops")
  public Map<String, Object> ops() {
    actor.admin();
    var data = new LinkedHashMap<String, Object>();
    data.put(
        "outbox", db.list("SELECT status,COUNT(*) AS count FROM outbox_events GROUP BY status"));
    data.put(
        "queuedSeckill", db.count("SELECT COUNT(*) FROM seckill_requests WHERE status='QUEUED'"));
    try {
      data.put(
          "workQueue",
          Objects.requireNonNullElseGet(
              broker.getQueueProperties(BrokerConfig.WORK), Properties::new));
      data.put(
          "deadQueue",
          Objects.requireNonNullElseGet(
              broker.getQueueProperties(BrokerConfig.DEAD), Properties::new));
      data.put("brokerAvailable", true);
    } catch (Exception e) {
      data.put("brokerAvailable", false);
    }
    return data;
  }

  @GetMapping("/reconciliation")
  public Map<String, Object> check() {
    actor.admin();
    return reconciliation.check();
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit() {
    actor.admin();
    return db.list("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 100");
  }

  @PostMapping("/outbox/{id}/replay")
  public Map<String, Object> replay(@PathVariable String id) {
    actor.admin();
    return tx.run(
        () -> {
          Problem.require(
              db.exec(
                      "UPDATE outbox_events SET status='NEW',attempts=0,next_attempt=UTC_TIMESTAMP(6) WHERE id=? AND status='DEAD'",
                      id)
                  == 1,
              409,
              "EVENT_NOT_DEAD");
          audit.log(actor.id(), "OUTBOX_REPLAY", id);
          return Map.of("id", id, "status", "NEW");
        });
  }

  public record RoomStatus(
      @NotNull @Pattern(regexp = "AVAILABLE|CLEANING|OUT_OF_SERVICE") String status) {}

  @PutMapping("/rooms/{id}/status")
  public Map<String, Object> room(@PathVariable long id, @Valid @RequestBody RoomStatus status) {
    var r =
        Problem.found(
            db.one(
                "SELECT rt.hotel_id FROM physical_rooms p JOIN room_types rt ON rt.id=p.room_type_id WHERE p.id=?",
                id));
    actor.hotel(Db.id(r, "hotel_id"));
    return tx.run(
        () -> {
          Problem.require(
              db.exec(
                      "UPDATE physical_rooms SET status=? WHERE id=? AND status<>'OCCUPIED'",
                      status.status(),
                      id)
                  == 1,
              409,
              "ROOM_OCCUPIED");
          audit.log(actor.id(), "PHYSICAL_ROOM_STATUS", id + ":" + status.status());
          return Map.of("id", id, "status", status.status());
        });
  }
}

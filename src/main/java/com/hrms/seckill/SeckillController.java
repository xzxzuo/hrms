package com.hrms.seckill;

import com.hrms.identity.Actor;
import com.hrms.inventory.CalendarController;
import com.hrms.observability.Audit;
import com.hrms.shared.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SeckillController {
  private final Db db;
  private final Tx tx;
  private final Actor actor;
  private final SeckillService seckill;
  private final Audit audit;

  public SeckillController(Db db, Tx tx, Actor actor, SeckillService seckill, Audit audit) {
    this.db = db;
    this.tx = tx;
    this.actor = actor;
    this.seckill = seckill;
    this.audit = audit;
  }

  public record Campaign(
      @Positive long roomTypeId,
      @NotNull LocalDate checkIn,
      @NotNull LocalDate checkOut,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
      @Min(1) @Max(100000) int quota) {}

  @PostMapping("/admin/hotels/{hotel}/campaigns")
  public Map<String, Object> create(@PathVariable long hotel, @Valid @RequestBody Campaign c) {
    actor.hotel(hotel);
    CalendarController.nights(c.checkIn(), c.checkOut());
    Problem.require(
        c.endsAt().isAfter(c.startsAt()) && c.endsAt().isAfter(Instant.now()),
        400,
        "INVALID_CAMPAIGN");
    var rt = Problem.found(db.one("SELECT hotel_id FROM room_types WHERE id=?", c.roomTypeId()));
    Problem.require(Db.id(rt, "hotel_id") == hotel, 403, "HOTEL_FORBIDDEN");
    var created =
        tx.run(
            () -> {
              long id =
                  db.insert(
                      "INSERT INTO seckill_campaigns(hotel_id,room_type_id,check_in,check_out,starts_at,ends_at,price,quota) VALUES(?,?,?,?,?,?,?,?)",
                      hotel,
                      c.roomTypeId(),
                      c.checkIn(),
                      c.checkOut(),
                      java.sql.Timestamp.from(c.startsAt()),
                      java.sql.Timestamp.from(c.endsAt()),
                      c.price(),
                      c.quota());
              audit.log(actor.id(), "CAMPAIGN_CREATE", Long.toString(id));
              return Map.of("id", id);
            });
    boolean ready = true;
    try {
      seckill.rebuild(((Number) created.get("id")).longValue());
    } catch (Exception e) {
      ready = false;
    }
    return Map.of("id", created.get("id"), "admissionReady", ready);
  }

  @GetMapping("/admin/hotels/{hotel}/campaigns")
  public List<Map<String, Object>> list(@PathVariable long hotel) {
    actor.hotel(hotel);
    return db.list(
        "SELECT * FROM seckill_campaigns WHERE hotel_id=? ORDER BY id DESC LIMIT 100", hotel);
  }

  @PostMapping("/seckill/{campaign}/requests")
  public Map<String, Object> request(@PathVariable long campaign) {
    return seckill.request(campaign, actor.id());
  }

  @GetMapping("/seckill/requests/{id}")
  public Map<String, Object> result(@PathVariable String id) {
    var r = Problem.found(db.one("SELECT * FROM seckill_requests WHERE id=?", id));
    actor.owner(Db.id(r, "user_id"));
    return r;
  }

  @PostMapping("/admin/campaigns/{id}/rebuild")
  public Map<String, Object> rebuild(@PathVariable long id) {
    var c = Problem.found(db.one("SELECT hotel_id FROM seckill_campaigns WHERE id=?", id));
    actor.hotel(Db.id(c, "hotel_id"));
    seckill.rebuild(id);
    return Map.of("id", id);
  }
}

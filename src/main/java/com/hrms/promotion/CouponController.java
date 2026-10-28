package com.hrms.promotion;

import com.hrms.identity.Actor;
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
public class CouponController {
  private final Db db;
  private final Tx tx;
  private final Actor actor;
  private final CouponService coupons;
  private final Audit audit;

  public CouponController(Db db, Tx tx, Actor actor, CouponService coupons, Audit audit) {
    this.db = db;
    this.tx = tx;
    this.actor = actor;
    this.coupons = coupons;
    this.audit = audit;
  }

  public record Coupon(
      @NotBlank @Size(max = 100) String name,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal discount,
      @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal minTotal,
      @NotNull @Future Instant expiresAt,
      @Min(1) int quota) {}

  @PostMapping("/admin/hotels/{hotel}/coupons")
  public Map<String, Object> create(@PathVariable long hotel, @Valid @RequestBody Coupon c) {
    actor.hotel(hotel);
    return tx.run(
        () -> {
          long id =
              db.insert(
                  "INSERT INTO coupons(hotel_id,name,discount,min_total,expires_at,quota) VALUES(?,?,?,?,?,?)",
                  hotel,
                  c.name(),
                  c.discount(),
                  c.minTotal(),
                  java.sql.Timestamp.from(c.expiresAt()),
                  c.quota());
          audit.log(actor.id(), "COUPON_CREATE", Long.toString(id));
          return Map.of("id", id);
        });
  }

  @GetMapping("/admin/hotels/{hotel}/coupons")
  public List<Map<String, Object>> list(@PathVariable long hotel) {
    actor.hotel(hotel);
    return db.list("SELECT * FROM coupons WHERE hotel_id=? ORDER BY id DESC LIMIT 100", hotel);
  }

  @PostMapping("/coupons/{id}/claim")
  public Map<String, Object> claim(@PathVariable long id) {
    return coupons.claim(actor.id(), id);
  }

  @GetMapping("/me/coupons")
  public List<Map<String, Object>> mine() {
    return db.list(
        "SELECT uc.id,uc.coupon_id,IF(uc.status='AVAILABLE' AND c.expires_at<=UTC_TIMESTAMP(6),'EXPIRED',uc.status) AS status,c.name,c.discount,c.expires_at FROM user_coupons uc JOIN coupons c ON c.id=uc.coupon_id WHERE uc.user_id=? ORDER BY uc.id DESC LIMIT 100",
        actor.id());
  }
}

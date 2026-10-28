package com.hrms.payment;

import com.hrms.booking.BookingService;
import com.hrms.identity.Actor;
import com.hrms.shared.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PaymentController {
  private final PaymentService payments;
  private final BookingService bookings;
  private final Db db;
  private final Actor actor;

  public PaymentController(PaymentService payments, BookingService bookings, Db db, Actor actor) {
    this.payments = payments;
    this.bookings = bookings;
    this.db = db;
    this.actor = actor;
  }

  public record Simulate(
      @Positive long bookingId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String providerRef,
      boolean success) {}

  public record Callback(
      @Positive long bookingId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String providerRef,
      @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal amount,
      boolean success,
      long timestamp) {}

  @PostMapping("/payment/simulate")
  public Map<String, Object> simulate(@Valid @RequestBody Simulate s) {
    actor.owner(Db.id(bookings.get(s.bookingId()), "user_id"));
    return payments.simulate(s.bookingId(), s.providerRef(), s.success());
  }

  @PostMapping("/payment/callback")
  public Map<String, Object> callback(
      @Valid @RequestBody Callback c, @RequestHeader("X-Signature") String signature) {
    return payments.callback(
        c.bookingId(), c.providerRef(), c.amount(), c.success(), c.timestamp(), signature);
  }

  @GetMapping("/bookings/{id}/payments")
  public Map<String, Object> list(@PathVariable long id) {
    actor.owner(Db.id(bookings.get(id), "user_id"));
    return Map.of(
        "payments",
        db.list("SELECT * FROM payments WHERE booking_id=?", id),
        "refunds",
        db.list("SELECT * FROM refunds WHERE booking_id=?", id));
  }

  @PostMapping("/bookings/{id}/refund")
  public Map<String, Object> refund(@PathVariable long id) {
    actor.owner(Db.id(bookings.get(id), "user_id"));
    return bookings.cancel(id);
  }

  @PostMapping("/admin/refunds/{id}/complete")
  public Map<String, Object> complete(@PathVariable long id) {
    var r =
        Problem.found(
            db.one(
                "SELECT b.hotel_id FROM refunds r JOIN bookings b ON b.id=r.booking_id WHERE r.id=?",
                id));
    actor.hotel(Db.id(r, "hotel_id"));
    return payments.complete(id);
  }
}

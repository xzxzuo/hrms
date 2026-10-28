package com.hrms.payment;

import com.hrms.booking.BookingService;
import com.hrms.messaging.Events;
import com.hrms.promotion.CouponService;
import com.hrms.shared.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
  private final Db db;
  private final Tx tx;
  private final BookingService bookings;
  private final CouponService coupons;
  private final Events events;
  private final String secret;

  public PaymentService(
      Db db,
      Tx tx,
      BookingService bookings,
      CouponService coupons,
      Events events,
      @Value("${hrms.callback-secret}") String secret) {
    this.db = db;
    this.tx = tx;
    this.bookings = bookings;
    this.coupons = coupons;
    this.events = events;
    this.secret = secret;
    Problem.require(secret.length() >= 16, 500, "CALLBACK_SECRET_TOO_SHORT");
  }

  public String sign(long booking, String ref, BigDecimal amount, boolean success, long timestamp) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  (booking
                          + "|"
                          + ref
                          + "|"
                          + amount.setScale(2).toPlainString()
                          + "|"
                          + success
                          + "|"
                          + timestamp)
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid callback");
    }
  }

  public Map<String, Object> callback(
      long booking,
      String ref,
      BigDecimal amount,
      boolean success,
      long timestamp,
      String signature) {
    Problem.require(
        Math.abs(Instant.now().getEpochSecond() - timestamp) <= 300, 401, "CALLBACK_EXPIRED");
    Problem.require(
        MessageDigest.isEqual(
            sign(booking, ref, amount, success, timestamp).getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)),
        401,
        "CALLBACK_SIGNATURE_INVALID");
    return tx.run(
        () -> {
          var b = bookings.locked(booking);
          Problem.require(
              amount.compareTo(Db.money(b, "total")) == 0, 409, "PAYMENT_AMOUNT_MISMATCH");
          var previous = db.one("SELECT * FROM payments WHERE provider_ref=?", ref);
          String status = success ? "SUCCEEDED" : "FAILED";
          if (previous != null) {
            Problem.require(
                Db.id(previous, "booking_id") == booking
                    && previous.get("status").equals(status)
                    && Db.money(previous, "amount").compareTo(amount) == 0,
                409,
                "PAYMENT_REF_CONFLICT");
            return bookings.get(booking);
          }
          Problem.require(
              !success
                  || db.count(
                          "SELECT COUNT(*) FROM payments WHERE booking_id=? AND status='SUCCEEDED'",
                          booking)
                      == 0,
              409,
              "PAYMENT_ALREADY_SETTLED");
          db.exec(
              "INSERT INTO payments(booking_id,provider_ref,amount,status) VALUES(?,?,?,?)",
              booking,
              ref,
              amount,
              status);
          if (!success) return bookings.get(booking);
          if (b.get("status").equals("PENDING_PAYMENT") && !bookings.due(b)) {
            bookings.state(b, "CONFIRMED", "CONFIRM");
            coupons.confirm(booking);
            events.add("PAYMENT_SUCCEEDED", booking);
          } else {
            bookings.expireLocked(b);
            bookings.refund(booking);
          }
          return bookings.get(booking);
        });
  }

  public Map<String, Object> simulate(long booking, String ref, boolean success) {
    var b = bookings.get(booking);
    long timestamp = Instant.now().getEpochSecond();
    BigDecimal amount = Db.money(b, "total");
    return callback(
        booking, ref, amount, success, timestamp, sign(booking, ref, amount, success, timestamp));
  }

  public Map<String, Object> complete(long refund) {
    return tx.run(
        () -> {
          var r = Problem.found(db.one("SELECT * FROM refunds WHERE id=? FOR UPDATE", refund));
          if (r.get("status").equals("REQUESTED")) {
            db.exec(
                "UPDATE refunds SET status='COMPLETED' WHERE id=? AND status='REQUESTED'", refund);
            events.add("REFUND_COMPLETED", r.get("booking_id"));
          }
          return db.one("SELECT * FROM refunds WHERE id=?", refund);
        });
  }
}

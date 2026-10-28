package com.hrms.pricing;

import com.hrms.inventory.CalendarController;
import com.hrms.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PricingService {
  private final Db db;
  private final Tx tx;

  public PricingService(Db db, Tx tx) {
    this.db = db;
    this.tx = tx;
  }

  public Map<String, Object> quote(
      long user, long room, LocalDate in, LocalDate out, int quantity) {
    var dates = CalendarController.nights(in, out);
    Problem.require(quantity > 0 && quantity <= 10, 400, "INVALID_STAY");
    return tx.run(
        () -> {
          var hotel =
              Problem.found(
                  db.one(
                      "SELECT r.id,h.timezone FROM room_types r JOIN hotels h ON h.id=r.hotel_id WHERE r.id=? AND r.status='ACTIVE' AND h.status='OPEN'",
                      room));
          Problem.require(
              !in.isBefore(LocalDate.now(ZoneId.of((String) hotel.get("timezone")))),
              400,
              "INVALID_STAY");
          var rates =
              db.list(
                  "SELECT stay_date,price FROM room_daily_rate WHERE room_type_id=? AND stay_date>=? AND stay_date<? ORDER BY stay_date",
                  room,
                  in,
                  out);
          Problem.require(rates.size() == dates.size(), 409, "RATE_MISSING");
          BigDecimal total =
              rates.stream()
                  .map(r -> Db.money(r, "price").multiply(BigDecimal.valueOf(quantity)))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          String id = UUID.randomUUID().toString();
          db.exec(
              "INSERT INTO quotes(id,user_id,room_type_id,check_in,check_out,quantity,total,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE))",
              id,
              user,
              room,
              in,
              out,
              quantity,
              total);
          for (var rate : rates)
            db.exec(
                "INSERT INTO quote_nights(quote_id,stay_date,price) VALUES(?,?,?)",
                id,
                rate.get("stay_date"),
                rate.get("price"));
          return Map.of(
              "quoteId", id, "total", total, "currency", "CNY", "expiresIn", 300, "nights", rates);
        });
  }
}

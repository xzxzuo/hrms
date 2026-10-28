package com.hrms;

import com.hrms.booking.BookingService;
import com.hrms.payment.PaymentService;
import com.hrms.pricing.PricingService;
import com.hrms.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
abstract class TestSupport {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    Containers.properties(r);
  }

  @Autowired Db db;
  @Autowired Tx tx;
  @Autowired PricingService pricing;
  @Autowired BookingService bookings;
  @Autowired PaymentService payments;

  record Fixture(long user, long hotel, long room, LocalDate in, int nights) {}

  long user() {
    return db.insert(
        "INSERT INTO users(email,password_hash) VALUES(?,'test-not-for-login')",
        UUID.randomUUID() + "@test.local");
  }

  Fixture fixture(int stock, int nights) {
    long uid = user();
    long hotel =
        db.insert(
            "INSERT INTO hotels(name,city,address,timezone,status,facilities,images) VALUES('Tx Hotel','Stockholm','x','UTC','OPEN','[]','[]')");
    long room =
        db.insert(
            "INSERT INTO room_types(hotel_id,name,bed_type,capacity,base_price,room_count) VALUES(?,'Queen','Queen',2,500,? )",
            hotel,
            Math.max(1, stock));
    var in = LocalDate.now(ZoneOffset.UTC).plusDays(5);
    for (int i = 0; i < nights; i++) {
      db.exec(
          "INSERT INTO room_daily_inventory(room_type_id,stay_date,total,available) VALUES(?,?,?,?)",
          room,
          in.plusDays(i),
          stock,
          stock);
      db.exec(
          "INSERT INTO room_daily_rate(room_type_id,stay_date,price) VALUES(?,?,500)",
          room,
          in.plusDays(i));
    }
    return new Fixture(uid, hotel, room, in, nights);
  }

  String quote(Fixture f, long user) {
    return (String)
        pricing.quote(user, f.room(), f.in(), f.in().plusDays(f.nights()), 1).get("quoteId");
  }

  long book(Fixture f) {
    return Db.id(
        bookings.create(f.user(), UUID.randomUUID().toString(), quote(f, f.user()), null), "id");
  }

  <T> List<T> concurrent(int count, IntFunction<T> task) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(count);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<T>>();
      for (int i = 0; i < count; i++) {
        final int j = i;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return task.apply(j);
                }));
      }
      if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("workers not ready");
      start.countDown();
      var values = new ArrayList<T>();
      for (var f : futures) values.add(f.get(90, TimeUnit.SECONDS));
      return values;
    }
  }

  void invariant(Fixture f) {
    org.assertj.core.api.Assertions.assertThat(
            db.count(
                "SELECT COUNT(*) FROM room_daily_inventory WHERE room_type_id=? AND (available<0 OR held<0 OR sold<0 OR total<>available+held+sold+blocked)",
                f.room()))
        .isZero();
  }
}

package com.hrms.identity;

import com.hrms.shared.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoData implements CommandLineRunner {
  private final Db db;
  private final Tx tx;
  private final PasswordEncoder passwords;
  private final String password;

  public DemoData(
      Db db, Tx tx, PasswordEncoder passwords, @Value("${DEMO_PASSWORD}") String password) {
    this.db = db;
    this.tx = tx;
    this.passwords = passwords;
    this.password = password;
  }

  public void run(String... args) {
    tx.run(
        () -> {
          db.exec(
              "INSERT IGNORE INTO users(email,password_hash) VALUES('admin@hrms.local',?)",
              passwords.encode(password));
          long user = Db.id(db.one("SELECT id FROM users WHERE email='admin@hrms.local'"), "id");
          db.exec(
              "INSERT IGNORE INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='ADMIN'",
              user);
          db.exec(
              "INSERT IGNORE INTO hotels(id,name,city,address,timezone,status,facilities,images) VALUES(1,'HRMS Demo Hotel','Stockholm','Demo Street 1','Europe/Stockholm','OPEN','[]','[]')");
          db.exec(
              "INSERT IGNORE INTO room_types(id,hotel_id,name,bed_type,capacity,base_price,room_count,status) VALUES(1,1,'Standard','Queen',2,500.00,100,'ACTIVE')");
          for (var d = LocalDate.now(ZoneOffset.UTC);
              d.isBefore(LocalDate.now(ZoneOffset.UTC).plusDays(60));
              d = d.plusDays(1)) {
            db.exec(
                "INSERT IGNORE INTO room_daily_inventory(room_type_id,stay_date,total,available) VALUES(1,?,100,100)",
                d);
            db.exec(
                "INSERT IGNORE INTO room_daily_rate(room_type_id,stay_date,price) VALUES(1,?,500.00)",
                d);
          }
          return null;
        });
  }
}

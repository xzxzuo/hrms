package com.hrms.hotel;

import com.hrms.identity.Actor;
import com.hrms.observability.Audit;
import com.hrms.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.ZoneId;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
public class HotelController {
  private final Db db;
  private final Tx tx;
  private final Actor actor;
  private final Audit audit;
  private final HotelCache cache;
  private final CatalogCache catalog;

  public HotelController(
      Db db, Tx tx, Actor actor, Audit audit, HotelCache cache, CatalogCache catalog) {
    this.db = db;
    this.tx = tx;
    this.actor = actor;
    this.audit = audit;
    this.cache = cache;
    this.catalog = catalog;
  }

  public record Hotel(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 80) String city,
      @NotBlank @Size(max = 255) String address,
      @NotBlank String timezone,
      @NotNull Set<@Size(max = 60) String> facilities,
      @NotNull List<@Size(max = 500) String> images) {}

  public record Status(@NotNull @Pattern(regexp = "DRAFT|OPEN|CLOSED") String status) {}

  @GetMapping("/hotels")
  public List<Map<String, Object>> search(
      @RequestParam(defaultValue = "") @Size(max = 80) String city,
      @RequestParam(defaultValue = "1") @Min(1) int guests,
      @RequestParam(defaultValue = "999999") @Min(0) int maxPrice,
      @RequestParam(defaultValue = "0") @Min(0) int offset) {
    return catalog.get(
        "search:"
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (city + "|" + guests + "|" + maxPrice + "|" + offset)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {},
        () ->
            db.list(
                "SELECT h.* FROM hotels h WHERE h.status='OPEN' AND (?='' OR h.city=?) AND EXISTS (SELECT 1 FROM room_types r WHERE r.hotel_id=h.id AND r.status='ACTIVE' AND r.capacity>=? AND r.base_price<=?) ORDER BY h.id LIMIT 50 OFFSET ?",
                city,
                city,
                guests,
                maxPrice,
                offset));
  }

  @GetMapping("/hotels/popular")
  public List<Map<String, Object>> popular() {
    return catalog.get(
        "popular",
        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {},
        () ->
            db.list(
                "SELECT h.id,h.name,h.city,COUNT(b.id) AS reservations FROM hotels h LEFT JOIN bookings b ON b.hotel_id=h.id AND b.status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT') WHERE h.status='OPEN' GROUP BY h.id,h.name,h.city ORDER BY reservations DESC,h.id LIMIT 10"));
  }

  @GetMapping("/hotels/{id}")
  public Map<String, Object> detail(@PathVariable @Min(1) long id) {
    return Problem.found(cache.get(id));
  }

  @PostMapping("/admin/hotels")
  public Map<String, Object> create(@Valid @RequestBody Hotel h) {
    actor.admin();
    ZoneId.of(h.timezone());
    return tx.run(
        () -> {
          long id =
              db.insert(
                  "INSERT INTO hotels(name,city,address,timezone,facilities,images,status) VALUES(?,?,?,?,?,?,'DRAFT')",
                  h.name(),
                  h.city(),
                  h.address(),
                  h.timezone(),
                  json(h.facilities()),
                  json(h.images()));
          audit.log(actor.id(), "HOTEL_CREATE", Long.toString(id));
          return Map.of("id", id);
        });
  }

  @PutMapping("/admin/hotels/{id}")
  public Map<String, Object> edit(@PathVariable long id, @Valid @RequestBody Hotel h) {
    actor.hotel(id);
    ZoneId.of(h.timezone());
    return tx.run(
        () -> {
          Problem.require(
              db.exec(
                      "UPDATE hotels SET name=?,city=?,address=?,timezone=?,facilities=?,images=? WHERE id=?",
                      h.name(),
                      h.city(),
                      h.address(),
                      h.timezone(),
                      json(h.facilities()),
                      json(h.images()),
                      id)
                  == 1,
              404,
              "NOT_FOUND");
          cache.changed(id);
          audit.log(actor.id(), "HOTEL_EDIT", Long.toString(id));
          return Map.of("id", id);
        });
  }

  @PutMapping("/admin/hotels/{id}/status")
  public Map<String, Object> status(@PathVariable long id, @Valid @RequestBody Status s) {
    actor.hotel(id);
    return tx.run(
        () -> {
          Problem.require(
              db.exec("UPDATE hotels SET status=? WHERE id=?", s.status(), id) == 1,
              404,
              "NOT_FOUND");
          cache.changed(id);
          audit.log(actor.id(), "HOTEL_STATUS", id + ":" + s.status());
          return Map.of("status", s.status());
        });
  }

  @PutMapping("/admin/hotels/{hotelId}/managers/{userId}")
  public Map<String, Object> manager(@PathVariable long hotelId, @PathVariable long userId) {
    actor.admin();
    return tx.run(
        () -> {
          db.exec(
              "INSERT IGNORE INTO hotel_managers(hotel_id,user_id) VALUES(?,?)", hotelId, userId);
          audit.log(actor.id(), "HOTEL_MANAGER_GRANT", hotelId + ":" + userId);
          return Map.of("hotelId", hotelId, "userId", userId);
        });
  }

  private String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
  }
}

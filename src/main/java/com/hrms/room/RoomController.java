package com.hrms.room;

import com.hrms.identity.Actor;
import com.hrms.observability.Audit;
import com.hrms.shared.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RoomController {
  private final Db db;
  private final Tx tx;
  private final Actor actor;
  private final Audit audit;
  private final com.hrms.hotel.CatalogCache cache;
  private final com.hrms.hotel.HotelCache hotelCache;

  public RoomController(
      Db db,
      Tx tx,
      Actor actor,
      Audit audit,
      com.hrms.hotel.CatalogCache cache,
      com.hrms.hotel.HotelCache hotelCache) {
    this.db = db;
    this.tx = tx;
    this.actor = actor;
    this.audit = audit;
    this.cache = cache;
    this.hotelCache = hotelCache;
  }

  public record RoomType(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 50) String bedType,
      @Min(1) @Max(20) int capacity,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
      @Min(1) int roomCount,
      @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

  @GetMapping("/hotels/{id}/room-types")
  public List<Map<String, Object>> list(@PathVariable long id) {
    return cache.get(
        "hotel-rooms:" + id,
        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {},
        () ->
            db.list(
                "SELECT r.* FROM room_types r JOIN hotels h ON h.id=r.hotel_id WHERE r.hotel_id=? AND r.status='ACTIVE' AND h.status='OPEN'",
                id));
  }

  @GetMapping("/room-types/{id}")
  public Map<String, Object> get(@PathVariable long id) {
    return Problem.found(
        cache.get(
            "room-type:" + id,
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {},
            () ->
                db.one(
                    "SELECT r.* FROM room_types r JOIN hotels h ON h.id=r.hotel_id WHERE r.id=? AND r.status='ACTIVE' AND h.status='OPEN'",
                    id)));
  }

  @PostMapping("/admin/hotels/{id}/room-types")
  public Map<String, Object> create(@PathVariable long id, @Valid @RequestBody RoomType r) {
    actor.hotel(id);
    return tx.run(
        () -> {
          long rid =
              db.insert(
                  "INSERT INTO room_types(hotel_id,name,bed_type,capacity,base_price,room_count,status) VALUES(?,?,?,?,?,?,?)",
                  id,
                  r.name(),
                  r.bedType(),
                  r.capacity(),
                  r.basePrice(),
                  r.roomCount(),
                  r.status());
          hotelCache.changed(id);
          audit.log(actor.id(), "ROOM_TYPE_CREATE", Long.toString(rid));
          return Map.of("id", rid);
        });
  }

  @PutMapping("/admin/room-types/{id}")
  public Map<String, Object> edit(
      @PathVariable long id, @RequestParam int version, @Valid @RequestBody RoomType r) {
    var old = Problem.found(db.one("SELECT * FROM room_types WHERE id=?", id));
    actor.hotel(Db.id(old, "hotel_id"));
    return tx.run(
        () -> {
          Problem.require(
              db.exec(
                      "UPDATE room_types SET name=?,bed_type=?,capacity=?,base_price=?,room_count=?,status=?,version=version+1 WHERE id=? AND version=?",
                      r.name(),
                      r.bedType(),
                      r.capacity(),
                      r.basePrice(),
                      r.roomCount(),
                      r.status(),
                      id,
                      version)
                  == 1,
              409,
              "VERSION_CONFLICT");
          hotelCache.changed(Db.id(old, "hotel_id"));
          audit.log(actor.id(), "ROOM_TYPE_EDIT", Long.toString(id));
          return Map.of("id", id, "version", version + 1);
        });
  }

  public record PhysicalRoom(
      @NotBlank @Size(max = 20) String roomNo,
      @NotNull @Pattern(regexp = "AVAILABLE|CLEANING|OUT_OF_SERVICE") String status) {}

  @PostMapping("/admin/room-types/{id}/rooms")
  public Map<String, Object> physical(@PathVariable long id, @Valid @RequestBody PhysicalRoom r) {
    var rt = Problem.found(db.one("SELECT * FROM room_types WHERE id=?", id));
    actor.hotel(Db.id(rt, "hotel_id"));
    return tx.run(
        () -> {
          long rid =
              db.insert(
                  "INSERT INTO physical_rooms(room_type_id,room_no,status) VALUES(?,?,?)",
                  id,
                  r.roomNo(),
                  r.status());
          audit.log(actor.id(), "PHYSICAL_ROOM_CREATE", Long.toString(rid));
          return Map.of("id", rid);
        });
  }

  @GetMapping("/admin/room-types/{id}/rooms")
  public List<Map<String, Object>> physicalList(@PathVariable long id) {
    var rt = Problem.found(db.one("SELECT * FROM room_types WHERE id=?", id));
    actor.hotel(Db.id(rt, "hotel_id"));
    return db.list("SELECT * FROM physical_rooms WHERE room_type_id=? ORDER BY room_no", id);
  }
}

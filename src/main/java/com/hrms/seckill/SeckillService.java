package com.hrms.seckill;

import com.hrms.booking.BookingService;
import com.hrms.messaging.*;
import com.hrms.shared.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SeckillService implements DomainEventHandler {
  private final Db db;
  private final Tx tx;
  private final StringRedisTemplate redis;
  private final RedissonClient locks;
  private final BookingService bookings;
  private final Events events;
  private final MeterRegistry metrics;
  private final boolean jobs;
  private final DefaultRedisScript<String> admit = new DefaultRedisScript<>();
  private final DefaultRedisScript<Long> compensate = new DefaultRedisScript<>(),
      limit = new DefaultRedisScript<>();

  public SeckillService(
      Db db,
      Tx tx,
      StringRedisTemplate redis,
      RedissonClient locks,
      BookingService bookings,
      Events events,
      MeterRegistry metrics,
      @Value("${hrms.jobs-enabled:true}") boolean jobs) {
    this.db = db;
    this.tx = tx;
    this.redis = redis;
    this.locks = locks;
    this.bookings = bookings;
    this.events = events;
    this.metrics = metrics;
    this.jobs = jobs;
    admit.setLocation(new ClassPathResource("lua/admit.lua"));
    admit.setResultType(String.class);
    compensate.setLocation(new ClassPathResource("lua/compensate.lua"));
    compensate.setResultType(Long.class);
    limit.setLocation(new ClassPathResource("lua/limit.lua"));
    limit.setResultType(Long.class);
  }

  public List<String> keys(long campaign) {
    String prefix = "hrms:seckill:{" + campaign + "}:";
    return List.of(prefix + "remaining", prefix + "users", prefix + "ready", prefix + "pending");
  }

  private RReadWriteLock gate(long campaign) {
    return locks.getReadWriteLock("hrms:seckill:{" + campaign + "}:gate");
  }

  private void acquire(RLock lock) {
    try {
      Problem.require(lock.tryLock(2, TimeUnit.SECONDS), 503, "ADMISSION_BUSY");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "INTERRUPTED");
    }
  }

  private Map<String, Object> campaign(long id) {
    return Problem.found(
        db.one(
            "SELECT *,starts_at<=UTC_TIMESTAMP(6) AND ends_at>UTC_TIMESTAMP(6) AND status='OPEN' AS valid FROM seckill_campaigns WHERE id=?",
            id));
  }

  public Map<String, Object> request(long campaign, long user) {
    var existing =
        db.one("SELECT * FROM seckill_requests WHERE campaign_id=? AND user_id=?", campaign, user);
    if (existing != null) return existing;
    var c = campaign(campaign);
    Problem.require(Db.number(c, "valid") == 1, 409, "CAMPAIGN_NOT_ACTIVE");
    try {
      Problem.require(
          Long.valueOf(1)
              .equals(redis.execute(limit, List.of("hrms:limit:user:{" + user + "}"), "5")),
          429,
          "RATE_LIMITED");
      if (!"1".equals(redis.opsForValue().get(keys(campaign).get(2)))) rebuild(campaign, false);
      var lock = gate(campaign).readLock();
      acquire(lock);
      String token = null;
      try {
        token =
            redis.execute(
                admit,
                keys(campaign),
                Long.toString(user),
                UUID.randomUUID().toString(),
                Long.toString(Instant.now().toEpochMilli()));
        Problem.require(!"SOLD_OUT".equals(token), 409, "CAMPAIGN_SOLD_OUT");
        Problem.require(token != null && !"NOT_READY".equals(token), 503, "ADMISSION_UNAVAILABLE");
        final String request = token;
        var result =
            tx.run(
                () -> {
                  var locked =
                      Problem.found(
                          db.one(
                              "SELECT *,starts_at<=UTC_TIMESTAMP(6) AND ends_at>UTC_TIMESTAMP(6) AND status='OPEN' AS valid FROM seckill_campaigns WHERE id=? FOR UPDATE",
                              campaign));
                  var old =
                      db.one(
                          "SELECT * FROM seckill_requests WHERE campaign_id=? AND user_id=?",
                          campaign,
                          user);
                  if (old != null) return old;
                  Problem.require(Db.number(locked, "valid") == 1, 409, "CAMPAIGN_NOT_ACTIVE");
                  Problem.require(
                      db.count(
                              "SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=? AND status IN ('QUEUED','SUCCESS')",
                              campaign)
                          < Db.number(locked, "quota"),
                      409,
                      "CAMPAIGN_SOLD_OUT");
                  db.exec(
                      "INSERT INTO seckill_requests(id,campaign_id,user_id) VALUES(?,?,?)",
                      request,
                      campaign,
                      user);
                  events.add("SECKILL_REQUESTED", request);
                  return db.one("SELECT * FROM seckill_requests WHERE id=?", request);
                });
        try {
          redis.opsForZSet().remove(keys(campaign).get(3), user + ":" + token);
        } catch (Exception ignored) {
          metrics.counter("hrms.seckill.finalize.failures").increment();
        }
        metrics.counter("hrms.seckill.admitted").increment();
        return result;
      } catch (Exception e) {
        if (token != null && token.length() == 36)
          try {
            if (db.one(
                    "SELECT id FROM seckill_requests WHERE campaign_id=? AND user_id=?",
                    campaign,
                    user)
                == null) release(campaign, user, token);
          } catch (Exception ignored) {
            metrics.counter("hrms.seckill.compensation.deferred").increment();
          }
        throw e;
      } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
      }
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      metrics.counter("hrms.seckill.unavailable").increment();
      throw new Problem(503, "ADMISSION_UNAVAILABLE");
    }
  }

  public void rebuild(long campaign) {
    rebuild(campaign, true);
  }

  private void rebuild(long campaign, boolean force) {
    var lock = gate(campaign).writeLock();
    if (force) acquire(lock);
    else {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!lock.tryLock()) {
        if ("1".equals(redis.opsForValue().get(keys(campaign).get(2)))) return;
        Problem.require(System.nanoTime() < deadline, 503, "ADMISSION_BUSY");
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Problem(503, "INTERRUPTED");
        }
      }
    }
    try {
      var keys = keys(campaign);
      if (!force && "1".equals(redis.opsForValue().get(keys.get(2)))) return;
      tx.run(
          () -> {
            var c =
                Problem.found(
                    db.one("SELECT * FROM seckill_campaigns WHERE id=? FOR UPDATE", campaign));
            redis.delete(keys.get(2));
            var rows =
                db.list(
                    "SELECT user_id,id FROM seckill_requests WHERE campaign_id=? AND status IN ('QUEUED','SUCCESS')",
                    campaign);
            redis.delete(keys);
            if (!rows.isEmpty()) {
              var users = new HashMap<String, String>();
              for (var row : rows)
                users.put(row.get("user_id").toString(), row.get("id").toString());
              redis.opsForHash().putAll(keys.get(1), users);
            }
            redis
                .opsForValue()
                .set(
                    keys.get(0),
                    Integer.toString(Math.max(0, Db.number(c, "quota") - rows.size())));
            redis.opsForValue().set(keys.get(2), "1");
            return null;
          });
      metrics.counter("hrms.seckill.rebuilds").increment();
    } finally {
      if (lock.isHeldByCurrentThread()) lock.unlock();
    }
  }

  public void release(long campaign, long user, String token) {
    redis.execute(compensate, keys(campaign), Long.toString(user), token);
  }

  public boolean supports(String type) {
    return type.equals("SECKILL_REQUESTED") || type.equals("SECKILL_COMPENSATE");
  }

  public void handle(String event, String type, String aggregate) {
    var r =
        Problem.found(db.one("SELECT * FROM seckill_requests WHERE id=? FOR UPDATE", aggregate));
    if (type.equals("SECKILL_COMPENSATE")) {
      release(Db.id(r, "campaign_id"), Db.id(r, "user_id"), aggregate);
      return;
    }
    if (!r.get("status").equals("QUEUED")) return;
    try {
      var b =
          tx.nested(
              () -> {
                var c =
                    Problem.found(
                        db.one(
                            "SELECT * FROM seckill_campaigns WHERE id=? FOR UPDATE",
                            r.get("campaign_id")));
                Problem.require(
                    db.exec(
                            "UPDATE seckill_campaigns SET quota_used=quota_used+1 WHERE id=? AND quota_used<quota",
                            c.get("id"))
                        == 1,
                    409,
                    "CAMPAIGN_SOLD_OUT");
                return bookings.createSeckill(Db.id(r, "user_id"), aggregate, c);
              });
      db.exec(
          "UPDATE seckill_requests SET status='SUCCESS',booking_id=? WHERE id=? AND status='QUEUED'",
          b.get("id"),
          aggregate);
      metrics.counter("hrms.seckill.results", "result", "success").increment();
    } catch (Problem e) {
      if (e.status >= 500) throw e;
      db.exec(
          "UPDATE seckill_requests SET status='FAILED',error_code=? WHERE id=? AND status='QUEUED'",
          e.code,
          aggregate);
      events.add("SECKILL_COMPENSATE", aggregate);
      metrics.counter("hrms.seckill.results", "result", "failed").increment();
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void scheduledRecovery() {
    if (jobs) recoverPending();
  }

  public void recoverPending() {
    for (var c :
        db.list(
            "SELECT id FROM seckill_campaigns WHERE ends_at>DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY) LIMIT 100")) {
      long campaign = Db.id(c, "id");
      try {
        var key = keys(campaign).get(3);
        var tokens =
            redis
                .opsForZSet()
                .rangeByScore(key, 0, Instant.now().minusSeconds(30).toEpochMilli(), 0, 100);
        if (tokens == null) continue;
        for (String member : tokens) {
          String[] parts = member.split(":", 2);
          var r = db.one("SELECT status FROM seckill_requests WHERE id=?", parts[1]);
          if (r == null || r.get("status").equals("FAILED"))
            release(campaign, Long.parseLong(parts[0]), parts[1]);
          else redis.opsForZSet().remove(key, member);
        }
      } catch (Exception e) {
        metrics.counter("hrms.seckill.recovery.failures").increment();
      }
    }
  }
}

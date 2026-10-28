package com.hrms.hotel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.messaging.Events;
import com.hrms.shared.*;
import io.micrometer.core.instrument.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class HotelCache {
  private final Db db;
  private final StringRedisTemplate redis;
  private final RedissonClient locks;
  private final ObjectMapper json;
  private final Events events;
  private final MeterRegistry metrics;
  private final boolean enabled;

  public HotelCache(
      Db db,
      StringRedisTemplate redis,
      RedissonClient locks,
      ObjectMapper json,
      Events events,
      MeterRegistry metrics,
      @Value("${hrms.cache-enabled:true}") boolean enabled) {
    this.db = db;
    this.redis = redis;
    this.locks = locks;
    this.json = json;
    this.events = events;
    this.metrics = metrics;
    this.enabled = enabled;
  }

  public String key(long id) {
    return "hrms:v1:hotel:" + id;
  }

  public Map<String, Object> get(long id) {
    Problem.require(id > 0, 400, "INVALID_ARGUMENT");
    if (!enabled) return load(id);
    try {
      String value = redis.opsForValue().get(key(id));
      if (value != null) {
        metrics.counter("hrms.cache.requests", "result", "hit").increment();
        return decode(value);
      }
      metrics.counter("hrms.cache.requests", "result", "miss").increment();
      var lock = locks.getLock("hrms:cache-rebuild:hotel:" + id);
      boolean acquired = lock.tryLock(50, 2000, TimeUnit.MILLISECONDS);
      try {
        value = redis.opsForValue().get(key(id));
        if (value != null) return decode(value);
        var row = load(id);
        if (acquired)
          redis
              .opsForValue()
              .set(
                  key(id),
                  row == null ? "null" : json.writeValueAsString(row),
                  Duration.ofSeconds(
                      row == null ? 30 : 600 + ThreadLocalRandom.current().nextInt(61)));
        return row;
      } finally {
        if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      metrics.counter("hrms.cache.requests", "result", "error").increment();
      return load(id);
    }
  }

  private Map<String, Object> decode(String value) throws Exception {
    return value.equals("null")
        ? null
        : json.readValue(value, new TypeReference<Map<String, Object>>() {});
  }

  private Map<String, Object> load(long id) {
    return db.one("SELECT * FROM hotels WHERE id=? AND status='OPEN'", id);
  }

  public void changed(long id) {
    events.add("CACHE_INVALIDATE", id);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              invalidateQuietly(id);
            }
          });
    else invalidateQuietly(id);
  }

  public void invalidate(long id) {
    redis.delete(key(id));
  }

  private void invalidateQuietly(long id) {
    try {
      invalidate(id);
    } catch (Exception e) {
      metrics.counter("hrms.cache.invalidation.failures").increment();
    }
  }
}

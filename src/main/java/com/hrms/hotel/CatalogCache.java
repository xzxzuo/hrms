package com.hrms.hotel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Versioned namespace prevents old reads from repopulating the current catalog generation. */
@Service
public class CatalogCache {
  public static final String EPOCH = "hrms:v1:catalog:epoch";
  private final StringRedisTemplate redis;
  private final RedissonClient locks;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final boolean enabled;

  public CatalogCache(
      StringRedisTemplate redis,
      RedissonClient locks,
      ObjectMapper json,
      MeterRegistry metrics,
      @Value("${hrms.cache-enabled:true}") boolean enabled) {
    this.redis = redis;
    this.locks = locks;
    this.json = json;
    this.metrics = metrics;
    this.enabled = enabled;
  }

  public <T> T get(String resource, TypeReference<T> type, Supplier<T> loader) {
    if (!enabled) return loader.get();
    try {
      String version = Objects.requireNonNullElse(redis.opsForValue().get(EPOCH), "0");
      String key = "hrms:v1:catalog:" + version + ":" + resource;
      String value = redis.opsForValue().get(key);
      if (value != null) {
        metrics.counter("hrms.cache.requests", "result", "hit").increment();
        return json.readValue(value, type);
      }
      metrics.counter("hrms.cache.requests", "result", "miss").increment();
      var lock = locks.getLock(key + ":rebuild");
      boolean acquired = lock.tryLock(50, 2000, TimeUnit.MILLISECONDS);
      try {
        value = redis.opsForValue().get(key);
        if (value != null) return json.readValue(value, type);
        T result = loader.get();
        if (acquired)
          redis
              .opsForValue()
              .set(
                  key,
                  json.writeValueAsString(result),
                  Duration.ofSeconds(
                      result == null ? 30 : 120 + ThreadLocalRandom.current().nextInt(31)));
        return result;
      } finally {
        if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      metrics.counter("hrms.cache.requests", "result", "error").increment();
      return loader.get();
    }
  }
}

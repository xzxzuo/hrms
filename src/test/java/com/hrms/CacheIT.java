package com.hrms;

import static org.assertj.core.api.Assertions.*;

import com.hrms.hotel.HotelCache;
import com.hrms.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class CacheIT extends TestSupport {
  @Autowired HotelCache cache;
  @Autowired StringRedisTemplate redis;

  @Test
  void hitNullCacheAndTransactionalInvalidation() {
    var f = fixture(1, 1);
    assertThat(cache.get(f.hotel()).get("name")).isEqualTo("Tx Hotel");
    assertThat(redis.hasKey(cache.key(f.hotel()))).isTrue();
    db.exec("UPDATE hotels SET name='Updated' WHERE id=?", f.hotel());
    assertThat(cache.get(f.hotel()).get("name")).isEqualTo("Tx Hotel");
    tx.run(
        () -> {
          cache.changed(f.hotel());
          return null;
        });
    assertThat(cache.get(f.hotel()).get("name")).isEqualTo("Updated");
    long missing = 99999999;
    assertThat(cache.get(missing)).isNull();
    assertThat(redis.opsForValue().get(cache.key(missing))).isEqualTo("null");
    assertThat(redis.getExpire(cache.key(missing))).isBetween(1L, 30L);
  }

  @Test
  void redisOutageFallsBackAndBookingStillCorrect() {
    var f = fixture(1, 1);
    var docker = Containers.REDIS.getDockerClient();
    docker.pauseContainerCmd(Containers.REDIS.getContainerId()).exec();
    try {
      assertThat(cache.get(f.hotel()).get("id")).isEqualTo(f.hotel());
      long id = book(f);
      assertThat(bookings.get(id).get("status")).isEqualTo("PENDING_PAYMENT");
      invariant(f);
    } finally {
      docker.unpauseContainerCmd(Containers.REDIS.getContainerId()).exec();
    }
  }
}

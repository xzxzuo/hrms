package com.hrms.shared;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class RedisConfig {
  @Bean(destroyMethod = "shutdown")
  RedissonClient redisson(
      @Value("${spring.data.redis.host}") String host,
      @Value("${spring.data.redis.port}") int port) {
    var c = new Config();
    c.setLazyInitialization(true);
    c.useSingleServer()
        .setAddress("redis://" + host + ":" + port)
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(8)
        .setTimeout(300)
        .setConnectTimeout(300)
        .setRetryAttempts(0);
    return Redisson.create(c);
  }
}

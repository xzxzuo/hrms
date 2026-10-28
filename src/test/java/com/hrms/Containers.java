package com.hrms;
import org.testcontainers.containers.*;import org.springframework.test.context.DynamicPropertyRegistry;
public class Containers {
 static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("hrms").withUsername("hrms").withPassword("test-only");
 static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
 static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");
 static {MYSQL.start();REDIS.start();RABBIT.start();}
 static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.rabbitmq.host",RABBIT::getHost);r.add("spring.rabbitmq.port",RABBIT::getAmqpPort);r.add("spring.rabbitmq.username",RABBIT::getAdminUsername);r.add("spring.rabbitmq.password",RABBIT::getAdminPassword);r.add("hrms.jwt-secret",()->"test-only-jwt-secret-at-least-32-characters");r.add("hrms.callback-secret",()->"test-only-callback-secret");r.add("hrms.jobs-enabled",()->false);r.add("management.server.port",()->0);}
}

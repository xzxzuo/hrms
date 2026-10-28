# Hotel Reservation & Management System (HRMS)

Java 21 / Spring Boot 3.5 / MyBatis / MySQL 8.4 / Redis / RabbitMQ 的酒店预订与运营管理重构项目。模块化单体；MySQL 为库存权威，秒杀配额与真实间夜库存独立。

本仓库为个人重构。原实习仅确认 Spring Boot/MyBatis 局部接口和事务、MySQL 建模与查询优化、Redis 常用数据缓存。新架构、支付、秒杀、MQ 及测试结果不能描述为原实习已完成工作。

## 开始运行

要求 Java 21、Docker（约 4 GB 可用内存）。

```sh
cp .env.example .env
sh scripts/start.sh
```

演示管理员：`admin@hrms.local`，密码见 `.env` 的 `DEMO_PASSWORD`。初始化只在 `demo` profile 生效，不删除已有数据。

- API / Swagger：[localhost:8088/swagger-ui.html](http://localhost:8088/swagger-ui.html)
- 健康检查：[localhost:8081/actuator/health](http://localhost:8081/actuator/health)
- RabbitMQ：[localhost:15672](http://localhost:15672)，凭据见 `.env`

本地直接运行：先 `docker compose up -d mysql redis rabbitmq`，将 `.env` 中 JWT_SECRET、CALLBACK_SECRET、DB_PASSWORD 导出，再 `./mvnw spring-boot:run`。只有显式添加 `SPRING_PROFILES_ACTIVE=demo` 与 `DEMO_PASSWORD` 才会创建演示数据。

## 测试

```sh
./mvnw -B verify
```

集成测试由 Testcontainers 创建独立真实 MySQL、Redis、RabbitMQ，不连接演示库。构建失败或 Docker 不可用将失败，不跳过验收。报告：`target/failsafe-reports`；实际执行计划：`target/explain.txt`。阶段状态和已测/未测边界见 [验证报告](docs/verification.md)。

## 设计文档

1. [整体架构设计 V1](docs/01-architecture-v1.md)
2. [模块职责与完整业务调用链](docs/02-modules-and-flows.md)
3. [数据库 ER、DDL 与索引](docs/03-database.md)
4. [订单状态机与库存一致性](docs/04-state-and-consistency.md)
5. [普通预订和秒杀时序](docs/05-sequences.md)
6. [分阶段计划与验收](docs/06-plan-and-acceptance.md)
7. [API 清单](docs/api.md)、[原始需求](docs/requirements.md)、[ADR](docs/adr)

单机 Compose 用于开发与故障演练，不代表生产高可用集群。不要对有价值的数据库执行清卷操作。

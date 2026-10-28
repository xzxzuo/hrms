# Java 后端项目复盘：HRMS

这是2026-10-28从空后端仓库完成的个人重构。代码和测试是本次工作证据；历史实习边界见最后一节。性能数字统一引用[实际性能报告](performance.md)，避免脱离环境背诵QPS。

## 1. 解决什么业务问题，为什么重构？

酒店前台维护房号的后台模型，不能直接覆盖线上多晚预订。比如11月1日入住、4日离店，需要占用1、2、3日的同房型库存；其中任一晚无房就不能成交。重构把房型日期可售库存与实体房号分开，建立报价、待支付、支付确认、取消、入住、退房闭环，并处理并发重复请求和外部系统故障。

可演示入口是[demo.py](../scripts/demo.py)：通过真实HTTP创建独立酒店、房型、日历、实体房间，完成入住退房、券支付退款、异步秒杀和最后对账。它不删除历史数据，演示结果存于evidence。

## 2. 架构如何设计，模块如何协作？

选择模块化单体。HTTP Controller验证参数和资源权限，Service编排MySQL事务，MyBatis执行参数绑定SQL，Redis缓存静态目录和准入，RabbitMQ承载可重放事件。领域包和事务边界见[模块设计](02-modules-and-flows.md)。

单体可以直接把订单、间夜、库存流水、优惠券和Outbox放进一个本地事务，避免为展示“分布式”而拆服务。`messaging.DomainEventHandler`是事件扩展接口，`BookingEventHandler`和`SeckillService`分别处理领域事件；`shared`只有事务、SQL执行和错误等基础能力，不反向依赖业务包。

## 3. 从查询到预订的完整调用链？

`GET /api/hotels` -> `HotelController`条件检索/目录缓存；`GET /room-types/{id}/calendar` -> 实时间夜和价格；`POST /quotes` -> `PricingService.quote`将每晚价格固化为quote_nights，报价5分钟有效；`POST /bookings` -> `BookingService.create`校验用户和报价、创建待支付订单、逐晚条件扣库存并写事件。

`POST /payment/simulate` -> `PaymentService.callback`验证签名、锁订单，未过期则held转sold、CONFIRMED；酒店授权人员调用check-in分配实体房号，check-out将房号变为CLEANING。旧日期sold作为已消费记录保留。交易测试`checkinCheckoutUsesPhysicalRoomWithoutReleasingHistoricalSold`覆盖这条链路。

## 4. 数据库和索引为什么这样设计？

[完整DDL](../src/main/resources/db/migration/V1__schema.sql)将room_daily_inventory聚簇主键设为(room_type_id,stay_date)，一次订单各晚行锁都能精确定位，日期升序保证同类事务锁顺序一致。CHECK约束保证`total=available+held+sold+blocked`且均非负。

bookings的(user_id,client_request_id)唯一防重复提交，支付provider_ref唯一，seckill_requests的(campaign_id,user_id)唯一，processed_messages主键防重复效果。`ix_booking_expiry(status,expires_at,id)`服务超时扫描，`ix_outbox_pending(status,next_attempt,id)`服务发布轮询。FoundationIT实际EXPLAIN的库存查询为PRIMARY/range/rows=3，原始结果在[evidence/explain.txt](evidence/explain.txt)。这只证明当前小样本计划，不证明百万行性能；更多实际计划见evidence/sql-plans.txt。

## 5. MyBatis、Service和事务各负责什么？

[SqlMapper](../src/main/java/com/hrms/shared/SqlMapper.java)提供MyBatis Provider，SQL文本只由代码内部定义；[Db](../src/main/java/com/hrms/shared/Db.java)将占位符转换为MyBatis绑定参数，不将用户值拼入SQL。MyBatis负责执行、结果映射和affectedRows，Service负责日期、状态、金额、授权语义。

[Tx](../src/main/java/com/hrms/shared/Tx.java)用TransactionTemplate明确最外层事务和有限死锁重试；已在事务中则加入，不在失败事务内部重试。秒杀业务性失败使用NESTED保存点回滚所有日期库存与活动配额后，外层保留FAILED请求与补偿事件。`TransactionIT.realMysqlDeadlockRetriesWholeTransaction`实际制造反向锁，验证整个失败尝试回滚。

## 6. Redis缓存什么，怎样一致？

[HotelCache](../src/main/java/com/hrms/hotel/HotelCache.java)缓存上架酒店详情，空值30s，正常10分钟加随机TTL；[CatalogCache](../src/main/java/com/hrms/hotel/CatalogCache.java)缓存房型、酒店房型列表、热门酒店和条件查询，2分钟加抖动。Redisson短锁保护热点重建；Redis超时回源MySQL。

酒店和房型修改在同一个DB事务写CACHE_INVALIDATE Outbox，提交后尽力删除酒店详情并递增catalog epoch，消费者后续重试。版本命名空间让旧查询不能污染新目录代次；详情缓存仍存在旧读晚回填窗口，由TTL收敛，不承诺强一致。库存展示和报价均绕过静态缓存，下单总是在DB校验。`CacheIT`验证命中、空值、失效、酒店关闭后的房型404以及Redis真实暂停降级。Bloom Filter是可选扩展，本实现使用输入校验+空值缓存，没有声称已部署Bloom Filter。

## 7. 高并发如何不超卖？

`InventoryService.hold`实际执行：

```sql
UPDATE room_daily_inventory
SET available=available-?, held=held+?, version=version+1
WHERE room_type_id=? AND stay_date=? AND available>=? AND sale_status='OPEN';
```

每一晚affectedRows必须为1。订单、每晚快照、流水、事件同事务提交，任何晚失败就抛错全回滚。没有先SELECT再无条件UPDATE。支付held转sold、取消held/sold转available也都是受条件保护的更新。

`TransactionIT`的最后一间20并发、部分重叠日期、其中一晚不足测试在真实MySQL验证；`SeckillIT`的1000用户/100配额/3晚验证活动与每日库存双上界。不能用这些正确性测试推导生产容量。

## 8. 为什么不用Redis锁作为库存正确性的最终保证？

Redis锁有租约、暂停、故障切换和锁丢失窗口，不能与MySQL提交原子绑定。即使Redisson锁失效，DB条件更新和唯一约束仍必须守住不变量。当前MySQL库存更新本身会获得行锁；状态机使用FOR UPDATE进行多步编排，version用于管理配置防覆盖，三者职责不同。

Redis活动read/write gate只协调准入与重建，不承担真实库存正确性。测试暂停Redis时普通预订仍成功，而秒杀新请求503，说明正确性没有寄托在Redis锁上。

## 9. 秒杀为什么使用Lua和MQ？

[admit.lua](../src/main/resources/lua/admit.lua)原子检查ready、用户资格和remaining，单用户重复返回稳定requestId。全部活动Key含同一个{campaignId}，符合Redis Cluster多Key同槽要求；本次部署测试使用单Redis，没有验证Cluster故障切换。

MQ把准入突发与DB订单处理解耦。HTTP成功返回QUEUED，消费者仍要在MySQL活动quota_used和每晚available上条件扣减。`SeckillService.handle`通过通用BookingService创建真实快照订单。quota10而真实库存3的测试只能产生3个订单，失败7个会补偿准入，不把Redis成功当作预订成功。

## 10. Redis、DB、MQ数据一致性怎样保证？

Lua准入后MySQL写seckill_requests与Outbox；持久化失败按requestId比较后回补Redis，补偿重复不能多加配额。Lua成功后进程崩溃留下pending租约，超过30s由recoverPending核对DB再补偿。Redis全丢失时用MySQL QUEUED/SUCCESS重建。

DB准入持久化还会锁活动行并验证活跃请求数<=quota，消费者再验证quota_used和真实库存，因此重建竞态最多影响准入体验，不能超卖。失败结果持久化后不允许同用户换ID重抢。成功后超时或取消释放每日库存，但活动配额采用不返还规则。这些是明确业务取舍，而不是两个库存计数必须相等。

## 11. 为什么需要Outbox？

如果先提交订单再直接publish，进程在两者之间崩溃会丢事件；先publish再提交则可能消费到回滚订单。[Events.add](../src/main/java/com/hrms/messaging/Events.java)参与原业务事务，[OutboxPublisher](../src/main/java/com/hrms/messaging/OutboxPublisher.java)之后锁NEW事件、持久化发送、等待Publisher Confirm和无returned message才标SENT。

broker收到而DB标记前崩溃会重复发送，接受这种至少一次模型。MySQL与RabbitMQ之间没有分布式exactly-once事务。`MessagingIT.brokerOutageRetainsOutboxThenRecovers`实际暂停broker，验证目标事件attempts=1仍NEW，恢复后正常完成。

## 12. 消息丢失、重复、顺序异常怎么处理？

[MessageProcessor](../src/main/java/com/hrms/messaging/MessageProcessor.java)把processed_messages唯一插入与业务效果放在一个事务。重复ID直接返回；提交后`MessageConsumer`才manual ACK。失败事务回滚，按200ms/1s/5s退避投递，重试耗尽reject到hrms.dead并计数告警。原消息在重试发布确认后才ACK。

订单处理依据数据库状态，不依赖事件先后顺序。例如到期事件晚于支付，消费者重新锁订单看到CONFIRMED便不会释放。消费端停后恢复、毒消息DLQ、重复通知和到期重查都有真实Rabbit测试。通知供应商被本地notifications表模拟，没有实际发邮件或短信。Outbox的8次失败进入DEAD后由运营修复并保留原eventId重放。

## 13. 支付与超时竞争怎么办？

`PaymentService.callback`和`BookingService.cancel/expire`都先锁同一个booking主键。在锁内检查PENDING_PAYMENT和DB到期时间，再同事务变状态和库存。按时支付先提交则确认；关闭先提交或回调到期则不确认，收到资金记录后进入REQUESTED退款。

`TransactionIT.paymentTimeoutAndUserCancelCompete`运行12轮三方竞争，最终只能CANCELLED/EXPIRED，各晚held/sold归零、available恢复且只生成一个退款。`earlyPaymentWinsAndScannerCannotRelease`验证反方向。HMAC覆盖bookingId|providerRef|金额两位小数|success|timestamp，限制5分钟；Mockito单测确认无效签名连DB事务都不触碰。

## 14. 实测多少，瓶颈在哪里，怎样优化？

完整数值、硬件边界和原始摘要见[performance.md](performance.md)，不能用脱离本机环境的数字回答。进程内并发测试与k6 HTTP压测分开记录；账号准备排除在自定义测量窗口外。HTTP秒杀的409售罄和429限流属于预期拒绝，必须同时报告它们和非预期失败率。

首轮1000 HTTP用户仍产生100个正确订单，但非预期失败率9.1%，其中有连接建立超时。发现冷活动初始化写锁竞争与有限监听积压队列不适合该突发负载，改为创建活动后预热、重建等待者观察ready而不都排写锁、Tomcat accept-count从100增至1000（仍有界）。复测结果如实记录，不删除失败证据。优化不改变DB最终校验。

## 15. 实际遇到哪些可复现困难？

- 优惠券重复提交：第二请求等到报价锁后看到券RESERVED，被错误拒绝。修复为锁报价后再次查已提交幂等订单；新增同券10并发测试。
- 死锁重试：事务内嵌调用不能在已失败事务里重试。Tx现在只在最外层重跑事务；真实MySQL两事务反向锁测试验证。
- Nginx健康检查：localhost在容器解析到IPv6，但仅监听IPv4，实际API可访问而health失败。改为127.0.0.1并验证所有七个服务healthy。
- k6公开接口错误：测试脚本给登录附带Bearer undefined，Security在permitAll前拒绝。修复为没有token时不发Authorization头。
- 首轮HTTP秒杀9.1%异常：按上一问的方法改进，失败摘要保留evidence/first-http-seckill.json，最终结果见性能报告。
- Flyway11.7.2在MySQL8.4.7启动时提示其验证版本上限警告。迁移、约束、事务和重启已实测；这不等于供应商认证。依赖由Boot BOM管理，生产升级前仍需按支持矩阵单独验收，未隐藏该警告。

## 历史实习与本次新增的事实边界

可确认历史：参与Spring Boot/MyBatis局部业务接口和事务相关逻辑，参与MySQL建模/查询优化，Redis缓存常用业务数据。工作区旧复盘还记有错误码、文档、联调等，不能据此扩写成独立设计全部系统。

本次个人新增：Java21/Boot3.5模块化架构、所有DDL、日期库存与报价、JWT/RBAC/酒店归属、完整订单支付退款状态机、券、Outbox/Rabbit、Lua秒杀/补偿、Testcontainers并发故障测试、k6实测、监控与运行手册。

推荐面试说法：我在原实习中参与了酒店后台局部后端工作，后来为了系统练习交易一致性，个人从零重构了HRMS；能展示本仓库代码、提交和真实测试结果，但不会把新增高并发架构说成实习时期的生产成果。

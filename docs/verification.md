# 实际验证记录

环境：2026-10-28，macOS arm64，Java 21.0.8，Docker Desktop 27.4.0；Maven 3.9.x；Testcontainers 1.21.3；真实 mysql:8.4.7 / redis:7.4.6-alpine / rabbitmq:3.13.7-management-alpine。

## Phase 0–1

- 六项设计文档、DDL、ER、ADR、API 清单已写入。
- 命令：`JAVA_HOME=<Java21> mvn -B verify`。
- 2026-10-28 15:57 Europe/Stockholm：BUILD SUCCESS，FoundationIT 2 tests，0 failures，0 errors，0 skipped；构建总耗时56.234s（包括首次镜像拉取）。
- 已验证：Flyway迁移、MySQL CHECK约束、注册/登录JWT、USER拒绝管理端、未授权酒店拒绝、三晚报价金额与离店日排除、日期非法拒绝。
- 修复记录：演示字符串转义、JWT权限泛型类型、测试JSON转义编译错误。修复后完整构建通过。
- 后续阶段尚未验收；没有实测QPS/p95/p99数据。

## Phase 2

2026-10-28 16:02 Europe/Stockholm，`mvn -B verify` BUILD SUCCESS：13 tests，0 failures/errors/skipped，24.854s。
新增交易测试11项：20用户争最后一间三晚库存、某晚不足全回滚、同键20并发幂等与异义冲突、重复支付/退款、12轮支付-超时-取消三方竞争、支付后扫描不释放、签名拒绝与失败支付和券返还、真实MySQL反向锁死锁重试、优惠券同键10并发、部分日期重叠、实体房号入住退房。
发现并修复：同报价锁竞争后必须再次检查已提交幂等订单，避免重复请求被已锁定优惠券误拒绝。真实死锁测试验证事务外重试，最终两行各增加2，失败事务没有残留更新。

## Phase 3

2026-10-28 16:03 Europe/Stockholm，`mvn -B verify`：15 tests，0 failures/errors/skipped，25.619s，BUILD SUCCESS。缓存测试验证命中、空值30s、事务提交后失效、Redis真实暂停时回源且普通下单正确。Redisson配置lazy initialization，避免Redis不在线阻止普通业务启动；超时和重试有界。缓存前后性能对比脚本与实测记录将在综合验收提供，当前不填QPS。

## Phase 4

2026-10-28 16:06 Europe/Stockholm，`mvn -B verify`：18 tests，0 failures/errors/skipped，35.991s，BUILD SUCCESS。确认真实persistent消息/Publisher Confirm、manual ACK、重复eventId只有一次notification效果；未知事件回滚processed标记，经200ms/1s/5s三档重试进入DLQ。暂停RabbitMQ时目标事件attempts=1且仍NEW，恢复后SENT并完成消费。修正事务帮助类：已处于事务时只加入，不在已失败事务内部重试；最外层执行全事务重试。

## Phase 5

2026-10-28 16:10 Europe/Stockholm，`mvn -B verify`：24 tests，0 failures/errors/skipped，45.094s，BUILD SUCCESS。1000个虚拟线程用户争100配额，100个成功订单，3晚各held=100/available=0；这是进程内service并发测试，不是HTTP QPS。另验证真实库存3/配额10只能成功3、失败7补偿，Redis丢失后从MySQL重建remaining=7；成功订单取消回补每日库存但不返还活动额度。同用户4并发一个请求；无效用户FK导致持久化失败后额度恢复；模拟进程崩溃留下Lua预扣租约，30s对账恢复；活动前后拒绝；Redis暂停503且无数据库请求；消费者停后恢复正确处理排队订单。

## Phase 6

2026-10-28 16:13 Europe/Stockholm，`mvn -B verify`：26 tests，0 failures/errors/skipped，57.337s，BUILD SUCCESS。hotel-scoped报表、未授权酒店403、对账识别人为制造的held异常、JWT拒绝、实际OpenAPI输出通过。
完整Docker Compose首次启动成功：MySQL/Redis/RabbitMQ/app/Nginx/Prometheus/Grafana均通过健康检查；Prometheus的hrms和rabbitmq targets均UP；Nginx实际提供OpenAPI；Grafana health为ok。修复Nginx健康检查localhost解析IPv6而只监听IPv4的问题，改127.0.0.1。

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

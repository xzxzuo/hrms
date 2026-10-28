# 分阶段任务计划与验收标准

阶段必须顺序验收，实际状态参见 verification.md。没有通过则停止进入下一阶段并保留可复现错误。

| 阶段 | 文件范围 | 可执行验收 |
|---|---|---|
| 0 设计 | docs/01..06、adr、requirements | 架构、不变量、ER、状态表、时序、API、决策齐全 |
| 1 基础 | pom、identity、hotel、room、inventory、pricing、db/migration、compose、FoundationIT | Java21 构建；真实 MySQL 迁移；注册登录；酒店隔离；房型/日历/报价；非法日期拒绝 |
| 2 交易 | booking、payment、TransactionIT | 同键并发稳定；最后一间只成功一次；跨晚失败全回滚；支付/取消/超时三方竞争；重叠日期 |
| 3 缓存 | hotel/HotelCache、redis 配置、CacheIT | 命中/空值/故障回源；提交后失效；基线对比脚本（实测后才填数） |
| 4 消息 | messaging、outbox 迁移、MessagingIT | publisher confirm；重复投递幂等；重试/DLQ；broker 中断恢复；超时扫描 |
| 5 秒杀 | seckill、lua、SeckillIT、k6 | 1000 用户争100配额；实际成功<=配额和所有日期库存；Redis故障拒绝；DB失败补偿 |
| 6 运维 | admin、observability、ops、compose | hotel scope、报表、审计、监控、健康检查、故障恢复 |
| 7 复盘 | 完整测试、docs/verification、interview、README | 从空数据库启动；演示端到端；真实报告；无伪造指标 |

## 架构决策
ADR-001 模块化单体；ADR-002 日期库存+本地事务；ADR-003 Outbox至少一次；ADR-004 秒杀准入不等于预订；ADR-005 事实边界。变更需先更新对应决策与测试。

## 非破坏性运行
迁移只使用新表和追加版本。演示初始化只 INSERT/UPSERT 指定标识，不删除用户数据。不提供自动 drop database 或 docker compose down -v 脚本。Testcontainers 仅管理自建临时容器。

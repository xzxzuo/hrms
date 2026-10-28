# 订单状态机和库存一致性设计

| 操作 | 原状态 | 目标状态 | 每晚库存 | 券 |
|---|---|---|---|---|
| 创建 | 无 | PENDING_PAYMENT | available -= qty, held += qty | AVAILABLE -> RESERVED |
| 按时支付 | PENDING_PAYMENT 且未到期 | CONFIRMED | held -= qty, sold += qty | RESERVED -> USED |
| 用户/后台取消 | PENDING_PAYMENT | CANCELLED | held -= qty, available += qty | RESERVED -> AVAILABLE/EXPIRED |
| 超时/过期时回调 | PENDING_PAYMENT 且到期 | EXPIRED | held -= qty, available += qty | 返还或过期 |
| 入住前退订 | CONFIRMED 且尚未入住 | CANCELLED | sold -= qty, available += qty | 已使用券不返还 |
| 入住 | CONFIRMED，酒店当地入住日已到 | CHECKED_IN | 无 | 无 |
| 退房 | CHECKED_IN | CHECKED_OUT | 无（历史 sold 保留） | 无 |
| 重放 | 已达到同一目标/终态 | 保持 | 无 | 无 |

退款状态独立：REQUESTED -> COMPLETED；模拟网关允许失败/成功和重复回调。取消后迟到支付仍记录收到资金并发起退款；不恢复订单或库存。一个 payment provider_ref 唯一，一个 refund payment_id 唯一。

## 事务和锁
READ COMMITTED。先锁订单（有订单时）-> 券 -> 活动（秒杀特例先请求/活动）-> 房型日期升序 -> 支付记录/流水/Outbox。普通创建先幂等插入再库存；其他订单不存在逆向获取活动锁。库存操作全在一个事务，任何日期 affectedRows != 1 抛异常全部回滚。UPDATE 自带行锁并在获锁后重新判断条件。显式 FOR UPDATE 用于状态编排，不替代条件 UPDATE。version 用于管理配置防覆盖；Redis 锁不与数据库提交原子绑定，不能保证库存最终正确性。

死锁/锁超时捕获 Spring 瞬态锁异常，在 TransactionTemplate 外最多三次指数退避+抖动，业务异常不重试。唯一键冲突在事务回滚后读已提交胜者并校验语义，不能在已标记 rollback-only 事务中继续写。

## 缓存与消息
Cache Aside 详情：hrms:v1:hotel:{id} TTL 10min + 0..60s；空值 30s；Redisson 短 tryLock 防热点回源；Redis 故障回源 MySQL。更新同事务写 CACHE_INVALIDATE Outbox，提交后尽力删缓存，异步重删兜底。并发旧读回填窗口仍存在，以 TTL 收敛；关键库存/报价绕过缓存。限流失败秒杀关闭，普通库存不依赖缓存。

Outbox 行锁 SKIP LOCKED + confirm/returns 只确认 broker 接收，不确认消费。失败回退并记录 attempts/next_attempt；次数耗尽告警，运营重放使用原事件 ID。消费者业务与 processed_messages 同事务，提交后 ACK；异常事务回滚后退避重投，达到上限 reject 到 DLQ。延迟队列 TTL + DLX 触发订单到期校验，数据库定时扫描兜底。

## Redis 准入恢复
所有活动 Key 含 {campaignId} hash tag。用户 map 保存 requestId，remaining 单独计数；Lua 只进行原子准入。持久化失败按 requestId compare-and-delete 补偿，避免重复返还。Redis 丢失必须关闭准入并使用 Redisson 活动锁，读取 MySQL 已接纳/成功请求重建，已失败不占配额，重新开启。重建与准入持相同锁；跨 MySQL 提交窗口通过锁覆盖准入+持久化或恢复租约解决，不能仅凭 Redis 计数承诺成功。

## 对账
聚合 booking_nights 对比 held/sold（按订单状态），验证 total=available+held+sold+blocked；聚合成功秒杀数<=quota。异常只告警，修复需审计及人工确认，不能直接覆盖尚在事务中的库存。

实现补充：持久化准入时另锁活动行并校验QUEUED/SUCCESS总数<=quota；冷活动创建后预热，重建等待者观察ready而非全部排写锁。目录缓存采用catalog epoch进行命名空间失效；热门酒店统计允许2分钟TTL窗口。

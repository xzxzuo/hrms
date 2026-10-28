# 模块职责与完整业务调用链

| 包 | 职责 | 依赖和事务边界 |
|---|---|---|
| identity | 注册、登录、JWT、RBAC、用户信息 | users/roles；注册事务创建用户与 USER 角色 |
| hotel | 酒店检索、详情、上下架、元数据 | identity 的酒店授权；写入+审计+缓存失效事件同事务 |
| room | 房型与实体房号 | hotel；房量不与房号直接等同 |
| inventory | 房型日期库存、条件更新、流水 | room；必须参加调用方订单事务 |
| pricing | 每晚房价、限时报价、金额快照 | room；报价持久化，订单核验用户与有效期 |
| booking | 下单、状态机、取消、入住、退房 | inventory/pricing/promotion/messaging；本地事务编排 |
| payment | 模拟网关、签名回调、退款 | booking；锁订单后记账，迟到支付自动进入退款 |
| promotion | 券定义、领取、锁定、核销、返还 | 同订单事务；用户+券唯一 |
| seckill | 活动、Lua 准入、持久化排队、异步落单 | booking/messaging；活动配额独立真实库存 |
| messaging | outbox、confirm、ACK、幂等、重试、DLQ | 通过事件调用应用服务；不承诺 exactly-once |
| admin | 酒店授权与运营报表 | 复用领域服务，不绕过状态机 |
| observability | traceId、指标、审计、对账 | 只记录非敏感字段 |

## 普通预订完整链路
1. 注册登录 -> JWT（主体取认证上下文，忽略客户端 userId）。
2. 城市/人数/价格筛选酒店 -> 房型 -> [入住,离店) 每晚库存/价日历。
3. 创建报价，固定每晚金额与到期时间 -> 提交 quoteId + 稳定 clientRequestId。
4. 查用户幂等键；相同语义返回既有订单，不同报价冲突；事务内唯一约束兜底。
5. 锁有效报价，建 PENDING_PAYMENT 订单，逐晚条件扣 available 增 held，写 booking_nights/ledger/outbox，提交。
6. 开发模拟支付签名回调 -> 锁订单 -> 未过期则 held 转 sold、状态 CONFIRMED；迟到成功记录退款，不能确认已取消订单。
7. 获授权酒店人员入住 -> CHECKED_IN；退房 -> CHECKED_OUT。实体房间分配需额外防重复占用。
8. 用户取消/超时关闭都先锁订单，合法条件成立才释放 held；已确认且入住前取消则释放 sold 并记退款。券锁定/核销/返还同步进行。

## 秒杀完整链路
JWT + 每用户 Redis 限流 -> 活动时间校验 -> 同 hash slot Lua 检查用户与配额 -> MySQL 唯一(user,campaign)请求+Outbox -> confirm 发布 -> manual ACK 消费 -> 锁请求、活动条件扣配额 -> 调用普通库存事务 -> 创建折扣快照订单 -> 更新 SUCCESS。
数据库无库存/活动配额时更新 FAILED，写补偿事件；Redis 必须以 requestId 为凭证幂等回补。持久化失败则即时补偿，崩溃窗口通过 Redis 短租约和持久化请求对账恢复。排队不代表成功；用户只能查询自己的 requestId。

成功订单超时后释放每日库存，但本活动采用“成功获得购买资格即消费配额”，不返还活动配额，且同用户不允许再次抢购。失败准入返还；quota 与每日 available 不等价。

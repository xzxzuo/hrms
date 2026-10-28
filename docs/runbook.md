# 运行、故障注入和恢复

`sh scripts/start.sh` 启动所有组件、健康检查与演示数据。`./mvnw verify` 使用独立Testcontainers，不删除演示数据。`sh scripts/load.sh cached|baseline|booking|seckill|rate` 生成本地results，不提交凭证或JWT。压测创建独立LoadTest酒店；不用重置已有库存。

## 运行入口
默认k6直接通过hrms_default网络访问app:8080；BASE_URL可覆盖，但宿主回环在1000连接突发时观察到少量连接超时。

API8080；Nginx8088；Actuator8081；Rabbit15672；Prometheus9090；Grafana3000（admin/本地.env密码）。所有宿主端口仅绑定127.0.0.1。Grafana预置HRMS Operations dashboard；Prometheus规则在ops/alerts.yml，本地告警可在9090/alerts查看，未配置外部通知渠道。

## 缓存基准
同样20VU、10s、同一GET酒店详情、同一硬件。`CACHE_ENABLED=false docker compose up -d --force-recreate app` 后运行baseline；恢复true后运行cached。预热至少一次并保留实际摘要。CACHE_ENABLED只影响静态详情，库存写入始终MySQL。一次短测不是容量极限或严格受控统计实验，不承诺缓存必然提高本机吞吐。

## 故障演练
自动测试实际暂停自建Redis/Rabbit容器并在finally恢复。手工演示仅操作本项目服务：`docker compose pause redis`，详情回源、普通预订继续、秒杀新请求503；`docker compose unpause redis`恢复。`docker compose pause rabbitmq`时订单状态+Outbox仍同事务提交，publisher超时后attempts增长；恢复后按退避发送。暂停应用消费者时，broker持久消息保留；恢复后processed_messages防重复。

Redis准入Lua后DB写失败，用requestId比较补偿；进程崩溃残留pending超过30s由recoverPending查MySQL再补偿。Redis整个活动数据丢失，read/write gate暂停准入，MySQL活动行锁下重建QUEUED/SUCCESS资格和剩余量。准入持久化另在MySQL活动行锁下校验活跃请求数<=quota，因此Redis恢复窗口仍不能突破数据库配额与实际房量。多Key全部使用{campaignId}。单Redis部署不声称已验证Redis Cluster failover。

## 积压和死信
GET /api/admin/ops（ADMIN）检查Outbox及队列消息量；Grafana看Hikari等待、API延迟、消息重试/死信。8次Outbox失败进入DEAD，先修复broker/路由后POST /api/admin/outbox/{id}/replay，保留原eventId。消费失败3次退避后进入hrms.dead，修复业务或坏消息后通过Rabbit管理界面检查/重投，不能无限重试。不得清空整个队列作为恢复手段。

## 对账与修复
GET /api/admin/reconciliation 对比held/sold与booking_nights及订单状态；比较quota_used与SUCCESS数。自动任务仅报告不覆盖库存。不变量破坏时先停止受影响房型销售，核对流水、支付、退款和审计，形成具体SQL修复方案；涉及破坏性操作先获批准。

退款模拟：POST /api/bookings/{id}/refund 在入住前按规则取消并生成退款；获授权人员POST /api/admin/refunds/{id}/complete。迟到支付自动创建REQUESTED退款，不确认已关闭订单。此项目不接真实支付或真实通知供应商；notification表用于可验证的模拟通知结果。

## 容量边界
单体连接池20、Tomcat100、MQ2..4消费者/prefetch10；HTTP、Redis、confirm和DB锁等待有界。MySQL故障则交易503、不会承诺成功。生产需TLS、secret管理、备份恢复、持久卷规划、监控外部通知及多副本/集群设计；Compose自身不提供这些能力。

最终源码验收包含MySQL真实暂停：API503且不创建订单。管理角色授予、酒店授权、房号创建与后台订单操作的审计均与变更处于同一事务。

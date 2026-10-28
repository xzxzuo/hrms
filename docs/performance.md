# 实际性能与正确性报告

日期：2026-10-28，Europe/Stockholm。macOS arm64；Docker Desktop 27.4.0；容器可用约7.75GiB内存；同机Java21应用、MySQL8.4.7、Redis7.4.6、RabbitMQ3.13.7、Prometheus/Grafana。未设置独占CPU；这是本机演示测试，不能当作生产容量承诺。

最终k6 1.3.0在hrms_default网络访问app:8080，绕过Nginx的按IP限流，直接测应用；Nginx完整业务链路另由demo.sh验证。缓存基线均20VU/10s，普通预订20VU各5次；秒杀1000VU各一次；限流1用户快速访问12个不同活动。每组创建独立合成酒店、房型和账号，不清空已有业务数据。

## 最终测量

| 场景 | 测量请求数 | 窗口秒 | 测量完成速率/s | p95 ms | p99 ms | 200/预期拒绝 | 非预期失败 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 未缓存酒店详情 | 34107 | 10.002 | 3410.02 | 15.35 | 26.57 | 34107/0 | 0.0% |
| 缓存酒店详情 | 56271 | 10.003 | 5625.41 | 10.06 | 19.18 | 56271/0 | 0.0% |
| 普通预订（报价+下单流程） | 100 | 1.225 | 81.63 | 295.45 | 325.10 | 100/0 | 0.0% |
| 1000用户秒杀准入 | 1000 | 1.871 | 534.47 | 1442.28 | 1479.72 | 100/900 | 0.0% |
| 单用户跨活动限流 | 12 | 0.056 | 214.29 | 7.85 | 8.20 | 5/7 | 0.0% |

完成速率用自定义测量开始/结束epoch的min/max计算，排除账号注册登录及fixture准备。booking的窗口包含报价和下单，但延迟仅测订单创建HTTP；rate只56ms、12次，是功能性限流证明，不能当作稳态容量数据。延迟为k6 duration+blocked，计入连接建立/等待；不是仅服务器处理时间。

秒杀900次409是预期售罄，因此原始http_req_failed可能接近90%；表中非预期失败专指不在约定状态集中的响应，不能把预期拒绝隐去。限流5次接纳、7次429。HTTP200只表示QUEUED；teardown等待真实订单，核验成功100、三晚每晚最多100，并再次调用数据库对账。

原始摘要：evidence/k6-baseline.json、k6-cached.json、k6-booking.json、k6-seckill.json、k6-rate.json。每次都要求unexpected_failure==0，失败命令返回非零；没有修改阈值来掩盖异常。

## 数据库与资源观察

约每3秒采样本项目docker stats、Hikari和SHOW GLOBAL STATUS，原始文件为evidence/*-resources.json。采样CPU中100%表示一个CPU核，可能超过100%。这是观察到的样本，不是连续真实峰值。

- 未缓存详情：MySQL CPU样本44.21%–64.45%，Threads_running最大3；缓存详情：MySQL CPU约0.95%–1.09%，Threads_running最大2。两组Hikari pending采样为0。采样本身也产生SQL请求。
- 普通预订：观察到Threads_running=15，MySQL CPU最高14.58%。样本跨setup和测量，不能称为完整峰值。
- 秒杀：SQL活动行、真实库存行会序列化有限的100个成功事务。原始CPU/连接样本覆盖账号准备和测量，短暂峰值可能漏采；最终订单与配额/库存对账无异常。
- 缓存后应用CPU仍明显使用多个核心，说明DB压力降低后HTTP、JSON、Redis和应用执行成本占比上升。这是资源样本推断，未做CPU profiling，不能宣称确认某个函数就是唯一瓶颈。

缓存阶段Prometheus历史计数实测hit=56243、miss=28、error=0，命中率99.9502%，原始查询结果见evidence/cache-counters.json。

消息状态快照单独保存在evidence/operational-snapshot.json：work ready=0/unacked=0，dead=0；expiry ready=408是正常待到期消息。work ready/unacked与Outbox待发送表示实时积压；Rabbit exporter的总ready还包含15分钟延迟到期队列，不能把其中等待到期的正常消息全部算作消费积压。当前采样不能证明绝对峰值或高可用恢复RTO。

## 失败首轮与优化过程

第一次经Docker Desktop宿主回环访问host.docker.internal:8080，1000请求产生100个正确订单，但非预期失败率9.1%，包含3个连接超时；原始摘要evidence/first-http-seckill.json保留。该轮与独立测试有时间重叠，仅作为故障现象证据，不用于最终容量对比。

修复冷启动竞争：活动创建后提交事务，再预热Redis；重建等待者观察ready，不全部等待写锁；Tomcat accept-count从100调整到有界1000。第二轮应用侧异常已消除，但宿主回环仍4个dial timeout（0.4%），见evidence/second-http-seckill.json。调整测试路径到同Compose网络，五组统一重测后全部零非预期失败。不能据此声称消除了所有网络环境的连接超时；宿主转发路径的限制已保留记录。

## 正确性与故障证据

进程内1000用户/100配额/3晚测试与HTTP测试分开。前者用真实Testcontainers，覆盖库存不足回滚、重复提交、支付/超时竞争、死锁、Redis和MQ暂停、消费者恢复、Lua孤儿租约补偿、数据重建。MQ毒消息在有界重试后进入DLQ。详见verification.md与src/test/java/com/hrms。

未执行多节点/跨可用区、Redis Cluster故障切换、真实支付供应商、长时间容量浸泡测试；没有把单机Compose描述为生产高可用系统。缓存对比为一次短测，缺少多轮随机顺序与置信区间，只能用于复现实验和面试讨论。

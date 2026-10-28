# REST API 清单

运行时 OpenAPI：`GET /v3/api-docs` 与 `/swagger-ui.html`。所有写入使用 JSON；金额以十进制表示，时间 UTC ISO-8601，日期 YYYY-MM-DD。JWT 通过 Authorization: Bearer。列表默认50条，有 offset 参数。所有权限以认证主体为准。

| 方法 | 路径 | 权限 / 语义 |
|---|---|---|
| POST | /api/auth/register、/api/auth/login | 公开；email/password；注册不可指定角色 |
| GET | /api/me | 当前主体 |
| PUT | /api/admin/users/{id}/roles/{role} | ADMIN |
| GET | /api/hotels?city=&guests=&maxPrice=&offset= | OPEN酒店 + ACTIVE房型过滤 |
| GET | /api/hotels/{id}、/api/hotels/{id}/room-types、/api/room-types/{id} | 公开上架资源 |
| POST / PUT | /api/admin/hotels、/api/admin/hotels/{id} | 创建限ADMIN；修改需要hotel scope |
| PUT | /api/admin/hotels/{id}/status | 上下架：DRAFT/OPEN/CLOSED |
| PUT | /api/admin/hotels/{hotelId}/managers/{userId} | ADMIN 授权酒店 |
| POST | /api/admin/hotels/{id}/room-types | hotel scope；名称床型容量基础价格物理数量 |
| PUT | /api/admin/room-types/{id}?version= | hotel scope，乐观版本 |
| GET / POST | /api/admin/room-types/{id}/rooms | hotel scope，实体房号 |
| GET | /api/room-types/{id}/calendar?checkIn=&checkOut= | 公开，[checkIn,checkOut) |
| PUT | /api/admin/room-types/{id}/inventory | hotel scope；date,total,blocked,saleStatus |
| PUT | /api/admin/room-types/{id}/rates | hotel scope；date,price |
| POST | /api/quotes | 用户；roomTypeId,checkIn,checkOut,quantity |
| POST / GET | /api/bookings | 用户；quoteId,clientRequestId,couponId可选 / 本人列表 |
| GET | /api/bookings/{id} | 本人 |
| POST | /api/bookings/{id}/cancel | 本人，幂等 |
| POST | /api/admin/bookings/{id}/{action} | hotel scope；cancel/check-in/check-out |
| POST | /api/payment/simulate | 用户；bookingId,providerRef,success |
| POST | /api/payment/callback | 开发网关签名，bookingId,providerRef,amount,success,timestamp；X-Signature |
| GET | /api/bookings/{id}/payments | 本人 |
| POST | /api/bookings/{id}/refund | 本人，入住前退订 |
| POST | /api/admin/refunds/{id}/complete | hotel scope，模拟退款完成 |
| POST / GET | /api/admin/hotels/{id}/coupons | hotel scope |
| POST | /api/coupons/{id}/claim | USER，重复领取返回原记录 |
| GET | /api/me/coupons | 本人 |
| POST | /api/admin/hotels/{id}/campaigns | hotel scope，指定房型/入住日期/活动数量/时间/逐晚价 |
| POST | /api/seckill/{campaignId}/requests | USER，排队准入；同用户稳定结果 |
| GET | /api/seckill/requests/{id} | 本人，QUEUED/SUCCESS/FAILED |
| GET | /api/admin/hotels/{id}/report?date= | hotel scope，经营报表 |
| GET | /api/admin/ops、/api/admin/reconciliation | ADMIN，消息/库存异常 |
| POST | /api/admin/outbox/{id}/replay | ADMIN，原eventId重放 |

后续阶段接口设计已列出；是否已实现与通过验收以 verification.md 为准，OpenAPI 只发布实际实现。

错误响应：`{code,traceId}`。400 INVALID_ARGUMENT/INVALID_STAY；401 INVALID_CREDENTIALS/JWT无效；403 HOTEL_FORBIDDEN/OWNER_FORBIDDEN；404 NOT_FOUND；409 INVENTORY_UNAVAILABLE/IDEMPOTENCY_CONFLICT/INVALID_STATE/QUOTE_EXPIRED；429 RATE_LIMITED；503 DATABASE_UNAVAILABLE/ADMISSION_UNAVAILABLE。Spring Security入口同样应统一JSON错误。

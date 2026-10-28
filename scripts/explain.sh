#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "$1"' sh "EXPLAIN SELECT * FROM room_daily_inventory WHERE room_type_id=1 AND stay_date>=CURRENT_DATE AND stay_date<DATE_ADD(CURRENT_DATE,INTERVAL 3 DAY) ORDER BY stay_date; EXPLAIN SELECT id FROM bookings WHERE status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100; EXPLAIN SELECT id FROM outbox_events WHERE status='NEW' AND next_attempt<=UTC_TIMESTAMP(6) ORDER BY next_attempt,id LIMIT 20;"

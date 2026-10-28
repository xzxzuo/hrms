-- KEYS: remaining/users/ready/pending; all share {campaignId}.
if redis.call('GET',KEYS[3]) ~= '1' then return 'NOT_READY' end
local prior=redis.call('HGET',KEYS[2],ARGV[1])
if prior then return prior end
local remaining=tonumber(redis.call('GET',KEYS[1]) or '0')
if remaining<=0 then return 'SOLD_OUT' end
redis.call('DECR',KEYS[1])
redis.call('HSET',KEYS[2],ARGV[1],ARGV[2])
redis.call('ZADD',KEYS[4],ARGV[3],ARGV[1]..':'..ARGV[2])
return ARGV[2]

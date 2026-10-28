-- requestId is the compensation token: repeated compensation cannot add quota twice.
if redis.call('GET',KEYS[3]) ~= '1' then return 0 end
if redis.call('HGET',KEYS[2],ARGV[1]) ~= ARGV[2] then return 0 end
redis.call('HDEL',KEYS[2],ARGV[1])
redis.call('ZREM',KEYS[4],ARGV[1]..':'..ARGV[2])
redis.call('INCR',KEYS[1])
return 1

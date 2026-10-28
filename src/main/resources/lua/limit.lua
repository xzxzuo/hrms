local count=redis.call('INCR',KEYS[1])
if count==1 then redis.call('PEXPIRE',KEYS[1],1000) end
if count>tonumber(ARGV[1]) then return 0 end
return 1

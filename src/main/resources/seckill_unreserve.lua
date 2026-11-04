-- KEYS[1] = stock key, KEYS[2] = reservation zset key
-- ARGV[1] = userId
if redis.call('ZREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
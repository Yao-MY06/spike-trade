-- KEYS[1] = stock key, KEYS[2] = reservation zset key
-- ARGV[1] = userId, ARGV[2] = reservation Unix seconds
local stockValue = redis.call('GET', KEYS[1])
if not stockValue then
    return 3
end

local stock = tonumber(stockValue)
if not stock then
    return 3
end

if redis.call('ZSCORE', KEYS[2], ARGV[1]) then
    return 2
end

if stock <= 0 then
    return 1
end

redis.call('DECR', KEYS[1])
local now = tonumber(ARGV[2])
if not now then
    now = tonumber(redis.call('TIME')[1])
end
redis.call('ZADD', KEYS[2], now, ARGV[1])
return 0
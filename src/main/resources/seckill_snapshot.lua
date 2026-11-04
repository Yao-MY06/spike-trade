-- KEYS[1] = stock key, KEYS[2] = reservation zset key
local stock = redis.call('GET', KEYS[1])
if not stock then
    stock = 'nil'
end
return stock .. ':' .. redis.call('ZCARD', KEYS[2])
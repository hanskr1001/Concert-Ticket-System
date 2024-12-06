--1.参数列表
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

--2.key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

--3.业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    return 1
end
--判断用户是否已经购买过该优惠券
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end

redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)

--发送消息
--redis.call('xadd','stream.orders','*','userId',userId,"voucherId",voucherId,'id',orderId)

return 0
-- Atomically move a PENDING share to READY and set its final expiry.
-- KEYS[1] = share:{code}   KEYS[2] = expiry:index
-- ARGV[1] = expiresAt (epoch ms)
if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then
  return 0
end
redis.call('HSET', KEYS[1], 'status', 'READY', 'expiresAt', ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[1], redis.call('HGET', KEYS[1], 'objectKey'))
return 1

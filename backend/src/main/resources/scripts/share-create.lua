-- Atomically create a share only if the code is free (no collisions).
-- KEYS[1] = share:{code}   KEYS[2] = expiry:index
-- ARGV[1] = TTL in ms      ARGV[2] = objectKey   ARGV[3] = expiresAt (epoch ms)
-- ARGV[4..] = hash field/value pairs
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('HSET', KEYS[1], unpack(ARGV, 4))
redis.call('PEXPIRE', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[2])
return 1

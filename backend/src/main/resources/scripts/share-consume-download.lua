-- Atomically consume one download of a READY share.
-- KEYS[1] = share:{code}   KEYS[2] = expiry:index
-- ARGV[1] = when the R2 object may be deleted after the last download (epoch ms)
-- Returns the downloads left after this one (>= 0), or -1 if not downloadable
-- (missing, still PENDING, or no downloads left).
if redis.call('HGET', KEYS[1], 'status') ~= 'READY' then
  return -1
end
local remaining = tonumber(redis.call('HGET', KEYS[1], 'remaining'))
if remaining == nil or remaining <= 0 then
  return -1
end
remaining = redis.call('HINCRBY', KEYS[1], 'remaining', -1)
if remaining == 0 then
  -- Last download: the code stops working now; the R2 object stays a short grace
  -- period so the presigned GET just handed out can still finish, then cleanup deletes it.
  local objectKey = redis.call('HGET', KEYS[1], 'objectKey')
  redis.call('DEL', KEYS[1])
  redis.call('ZADD', KEYS[2], ARGV[1], objectKey)
end
return remaining

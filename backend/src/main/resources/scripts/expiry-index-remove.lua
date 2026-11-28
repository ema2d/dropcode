-- Remove an objectKey from expiry:index only if it is still due.
-- If its score was pushed into the future meanwhile (e.g. the share was completed),
-- keep it so the object is not forgotten.
-- KEYS[1] = expiry:index   ARGV[1] = objectKey   ARGV[2] = now (epoch ms)
local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if score and tonumber(score) <= tonumber(ARGV[2]) then
  return redis.call('ZREM', KEYS[1], ARGV[1])
end
return 0

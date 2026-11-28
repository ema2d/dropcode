-- Fixed-window counter: count one hit and start the window on the first hit.
-- KEYS[1] = counter key   ARGV[1] = window in ms
-- Returns the count in the current window (including this hit).
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count

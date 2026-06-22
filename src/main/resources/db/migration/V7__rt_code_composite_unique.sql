-- RT codes are unique within an RW, not globally (spec §3): two different RWs may each have
-- an "RT 01". Replace the global UNIQUE on rt_code (auto-named rt_rt_code_key by V1) with a
-- composite UNIQUE on (rw_id, rt_code). rw_id was added in V4 and is NOT NULL.
ALTER TABLE rt DROP CONSTRAINT IF EXISTS rt_rt_code_key;
ALTER TABLE rt ADD CONSTRAINT uq_rt_rw_code UNIQUE (rw_id, rt_code);

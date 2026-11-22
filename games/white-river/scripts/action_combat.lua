-- White River Online: action combat, prototype (ADR 0017, phase A2).
--
-- Steps the data in data/action_skills and data/weapon_sets: a skill is a timeline in ticks with hitboxes, a weapon set
-- is the combo graph. It only runs when game.json selects "combat.module": "lua_combat"; the game ships on "realtime"
-- until the Kotlin ActionCombatModule (A5) replaces this prototype, so nothing here is reachable in normal play.
--
-- Hooks are stateless (a new Lua state per call), so everything that must survive a tick lives in script state:
--   "ac:<character>"   the character's skill in progress, buffered input and cooldowns (all values strings)
--   "react:<npc>"      the last reaction a hit left on a creature (reaction, until tick, attacker)
--
-- Input: the client sends c_action "input" with params { token = "LMB" } (see InputTokens), or the plain attack packet,
-- which counts as "LMB". Time is world.tick; geometry is integer pixels with a facing locked when the skill starts.
--
-- Not here yet (later phases): movement during a skill (A3/A4), guard, super armor and invulnerability (A4), projectile
-- hitboxes (A5), reactions that change what a creature does, and the game's own damage formula (A5; the one below is
-- a stand-in so the chain can be measured).

local STATE_PREFIX = "ac:"
local REACT_PREFIX = "react:"
local TARGET_RADIUS = 12          -- a body is a disc this wide, in pixels
local DEFAULT_WINDOW = 8          -- edge window when the edge names none
local COMBO_GRACE = 20            -- ticks after a skill ends that its chain stays alive (ActionCombatLimits)
local MAX_SET_ID = 32             -- weapon sets are looked up by id; hooks cannot list them

local FACING = {
    UP = { 0, -1 }, DOWN = { 0, 1 }, LEFT = { -1, 0 }, RIGHT = { 1, 0 },
    UP_LEFT = { -1, -1 }, UP_RIGHT = { 1, -1 }, DOWN_LEFT = { -1, 1 }, DOWN_RIGHT = { 1, 1 },
}

local function num(v, default)
    local n = tonumber(v)
    if n == nil then return default end
    return n
end

-- cos of an angle in degrees from a Taylor series: only + - * /, which are exact IEEE operations, so it gives the same
-- bits on every machine (math.cos is not guaranteed to).
local function cos_deg(deg)
    local x = deg * 3.141592653589793 / 180
    local x2 = x * x
    local term, sum = 1, 1
    for n = 1, 12 do
        term = -term * x2 / ((2 * n - 1) * (2 * n))
        sum = sum + term
    end
    return sum
end

local function facing_vector(name)
    local f = FACING[name] or FACING.DOWN
    local len = math.sqrt(f[1] * f[1] + f[2] * f[2])
    return f[1] / len, f[2] / len
end

local function copy(t)
    local c = {}
    for k, v in pairs(t) do c[k] = v end
    return c
end

-- ---------------------------------------------------------------------------------------------------------------
-- Combo graph lookups

local function choose_set(world, player)
    for id = 1, MAX_SET_ID do
        local set = world.get_weapon_set(id)
        if set and (set.slot or "MAIN") == "MAIN" and num(set.levelReq, 1) <= player.level then
            local ok = (set.classIds == nil) or (#set.classIds == 0)
            if not ok then
                for _, c in ipairs(set.classIds) do
                    if c == player.class_id then ok = true end
                end
            end
            if ok then return id, set end
        end
    end
    return nil, nil
end

-- The edge out of `from` for `token`. With `hit` nil any edge counts (used to decide whether to buffer an early press);
-- otherwise an edge that requires a hit is taken only when the skill connected, and is preferred when it does.
local function find_edge(set, from, token, hit)
    local plain
    for _, e in ipairs(set.edges or {}) do
        if e.fromSkillId == from and e.input == token then
            if e.requiresHit then
                if hit == nil or hit then return e end
            else
                plain = e
            end
        end
    end
    return plain
end

local function find_entry(set, token)
    for _, e in ipairs(set.entries or {}) do
        if e.input == token then return e end
    end
    return nil
end

local function skill_length(skill)
    return num(skill.startupTicks, 0) + num(skill.activeTicks, 0) + num(skill.recoveryTicks, 0)
end

local function cancel_tick(skill)
    return skill.cancelFromTick or (num(skill.startupTicks, 0) + num(skill.activeTicks, 0))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Starting a skill

-- Returns true when the skill started. `st` is the character's state table and is changed in place.
local function start_skill(world, player, st, skill_id, now)
    local skill = world.get_action_skill(skill_id)
    if not skill then return false end
    if num(skill.levelReq, 1) > player.level then return false end
    local cd = num(st["cd" .. skill_id], 0)
    if now < cd then return false end
    for vital, amount in pairs(skill.costs or {}) do
        local v = player.vitals[vital]
        if not v or v.cur < amount then
            world.send_message(player.id, "Not enough " .. vital .. " for " .. skill.name .. ".", "#94A3B8")
            return false
        end
    end
    -- Costs in a fixed order, so the effect log is the same however the table iterates.
    local names = {}
    for vital, _ in pairs(skill.costs or {}) do names[#names + 1] = vital end
    table.sort(names)
    for _, vital in ipairs(names) do
        world.consume_vital(player.id, vital, skill.costs[vital])
    end
    if skill.cooldownTicks and skill.cooldownTicks > 0 then
        st["cd" .. skill_id] = now + skill.cooldownTicks
    end
    st.skill = skill_id
    st.start = now
    st.done = -1
    st.hit = 0
    st.hits = ";"
    for g = 0, 15 do st["n" .. g] = nil end
    st.freeze = 0
    st.dir = player.direction
    st.bin = nil
    st.bat = nil
    return true
end

-- ---------------------------------------------------------------------------------------------------------------
-- Hitboxes

local function in_shape(hb, cx, cy, fx, fy, tx, ty)
    local rx, ry = -fy, fx
    local ox = cx + fx * num(hb.forwardPixels, 0) + rx * num(hb.sidePixels, 0)
    local oy = cy + fy * num(hb.forwardPixels, 0) + ry * num(hb.sidePixels, 0)
    local dx, dy = tx - ox, ty - oy
    if hb.shape == "RECT" then
        local l = dx * fx + dy * fy
        local s = dx * rx + dy * ry
        return l >= -TARGET_RADIUS and l <= num(hb.lengthPixels, 0) + TARGET_RADIUS
            and math.abs(s) <= num(hb.widthPixels, 0) / 2 + TARGET_RADIUS
    end
    local reach = num(hb.radiusPixels, 0) + TARGET_RADIUS
    local d2 = dx * dx + dy * dy
    if d2 > reach * reach then return false end
    if hb.shape == "CIRCLE" or num(hb.arcDegrees, 360) >= 360 or d2 == 0 then return true end
    local dist = math.sqrt(d2)
    return dx * fx + dy * fy >= dist * cos_deg(num(hb.arcDegrees, 360) / 2)
end

local function hitbox_reach_tiles(hb)
    local reach = math.max(num(hb.radiusPixels, 0), num(hb.lengthPixels, 0), num(hb.widthPixels, 0))
        + math.abs(num(hb.forwardPixels, 0)) + math.abs(num(hb.sidePixels, 0)) + TARGET_RADIUS
    return math.floor(reach / 32) + 1
end

local function hit_recorded(st, key)
    return string.find(st.hits or ";", ";" .. key .. ";", 1, true) ~= nil
end

-- Placeholder melee formula (the game's own formula arrives with the Kotlin module in A5).
local function damage_for(player, npc, percent)
    local a = player.effective_attributes
    local d = npc and npc.attributes or {}
    local raw = (a.str or 10) * 2 + math.floor((a.agi or 10) / 3)
    local base = math.max(1, raw - (d.vit or 0))
    return math.max(1, math.floor(base * percent / 100))
end

-- Tests every live hitbox of `skill` at skill tick `u`; records hits in `st` and emits damage.
local function eval_hitboxes(world, player, st, skill, u, now)
    local fx, fy = facing_vector(st.dir)
    local best_stop = 0
    for i, hb in ipairs(skill.hitboxes or {}) do
        if u >= hb.fromTick and u <= hb.toTick and not hb.projectile then
            local group = num(hb.hitGroup, 0)
            local limit = num(hb.maxTargets, 0)
            local entities = world.get_entities_in_range(player.map_id, player.x, player.y, hitbox_reach_tiles(hb))
            for _, e in ipairs(entities) do
                local key = "g" .. group .. ":" .. e.id
                local count = num(st["n" .. group], 0)
                if not e.is_player and not hit_recorded(st, key) and (limit == 0 or count < limit)
                    and in_shape(hb, player.px, player.py, fx, fy, e.px, e.py) then
                    local h = hb.hit or {}
                    local npc = world.get_npc(player.map_id, e.id)
                    world.damage_npc(player.map_id, e.id, damage_for(player, npc, num(h.damagePercent, 100)), "hp", player.id)
                    st.hits = (st.hits or ";") .. key .. ";"
                    st["n" .. group] = count + 1
                    st.hit = 1
                    if h.reaction and h.reaction ~= "NONE" or h.statusEffectType then
                        world.set_state(REACT_PREFIX .. e.id, {
                            reaction = h.reaction or "NONE",
                            untilTick = now + num(h.reactionTicks, 0),
                            status = h.statusEffectType or "",
                            by = player.id,
                        })
                    end
                    best_stop = math.max(best_stop, num(h.hitStopTicks, 0))
                end
            end
        end
    end
    -- Hit-stop freezes the timeline for a few ticks: the heavier the hit, the longer the freeze.
    if best_stop > 0 then st.freeze = num(st.freeze, 0) + best_stop end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Input

-- Tries to act on `token` for a character whose state is `st`. Returns true when a skill started or the press was
-- buffered (so the caller keeps the state).
local function handle_input(world, player, st, token, now, from_buffer)
    local set_id, set = nil, nil
    if st.set then
        set_id = tonumber(st.set)
        set = world.get_weapon_set(set_id)
    end
    if not set then
        set_id, set = choose_set(world, player)
        if not set then return false end
        st.set = set_id
    end

    local current = num(st.skill, 0)
    if current ~= 0 then
        local skill = world.get_action_skill(current)
        if skill then
            local t = now - num(st.start, now)
            local cancel = cancel_tick(skill)
            local edge = find_edge(set, current, token, tostring(st.hit) == "1")
            if edge and t >= cancel and t <= cancel + num(edge.windowTicks, DEFAULT_WINDOW) then
                return start_skill(world, player, st, edge.toSkillId, now)
            end
            -- Too early for the edge: remember the press for a few ticks (an edge that needs a hit may still earn it).
            local any = find_edge(set, current, token, nil)
            if any and t < cancel and cancel - t <= num(set.inputBufferTicks, 0) and not from_buffer then
                st.bin = token
                st.bat = now
                return true
            end
            -- Idle but the chain is not what this press asked for: fall through to a fresh entry when recovery is over.
            if t < skill_length(skill) then return false end
        end
    end
    local entry = find_entry(set, token)
    if entry then return start_skill(world, player, st, entry.skillId, now) end
    return false
end

local function on_input(char_id, token, world)
    local player = world.get_player(char_id)
    if not player then return end
    local key = STATE_PREFIX .. char_id
    local st = copy(world.get_state(key) or {})
    if handle_input(world, player, st, token, world.tick, false) then
        world.set_state(key, st)
    end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Tick

local function step_character(world, key, now)
    local char_id = string.sub(key, #STATE_PREFIX + 1)
    local player = world.get_player(char_id)
    if not player then
        world.set_state(key, nil)
        return
    end
    local st = copy(world.get_state(key) or {})
    local current = num(st.skill, 0)
    if current == 0 then return end
    local skill = world.get_action_skill(current)
    if not skill then
        st.skill = nil
        world.set_state(key, st)
        return
    end

    if num(st.freeze, 0) > 0 then
        st.freeze = num(st.freeze, 0) - 1
        st.start = num(st.start, now) + 1
        world.set_state(key, st)
        return
    end

    local length = skill_length(skill)
    local t = now - num(st.start, now)
    local last = math.min(t, length)
    -- Evaluate every skill tick since the last one, so a skill started between ticks loses none.
    for u = num(st.done, -1) + 1, last do
        eval_hitboxes(world, player, st, skill, u, now)
    end
    st.done = math.max(num(st.done, -1), last)

    if st.bin then
        local set = world.get_weapon_set(num(st.set, 0))
        local token, at = st.bin, num(st.bat, now)
        if set and now - at > num(set.inputBufferTicks, 0) then
            st.bin = nil
            st.bat = nil
        elseif t >= cancel_tick(skill) then
            st.bin = nil
            st.bat = nil
            handle_input(world, player, st, token, now, true)
        end
    end

    if num(st.skill, 0) == current and t > length + COMBO_GRACE then
        st.skill = nil
        st.hit = nil
    end
    world.set_state(key, st)
end

local function on_tick(world)
    local now = world.tick
    for _, key in ipairs(world.state_keys(STATE_PREFIX)) do
        step_character(world, key, now)
    end
end

register.combat({
    on_attack = function(char_id, world)
        on_input(char_id, "LMB", world)
    end,
    handle_action = function(char_id, action_id, params, world)
        if action_id == "input" and params.token then
            on_input(char_id, params.token, world)
        end
    end,
    on_tick_players = function(world)
        on_tick(world)
    end,
})

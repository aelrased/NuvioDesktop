-- Nuvio custom OSC for mpv — renders inside the video surface
-- Works on both X11 and Wayland (no transparency issues)
local mp = require 'mp'
local utils = require 'mp.utils'
local assdraw = require 'mp.assdraw'

-- Config
local VIS_TIMEOUT = 3
local BAR_H = 4
local BOTTOM_PAD = 55
local TOP_PAD = 35

-- State
local State = {
    pos = 0,
    dur = 0,
    paused = false,
    loading = false,
    visible = true,
    hover_progress = nil, -- nil = not hovering seekbar
    title = '',
    mouse_y = -1,
    w = 1920,
    h = 1080,
}

local overlay = nil
local hide_timer = nil

local function fmt_time(s)
    if not s or s < 0 then return '0:00' end
    s = math.floor(s)
    return string.format('%d:%02d', math.floor(s / 60), s % 60)
end

local function update_size()
    State.w = mp.get_property_number('osd-width', 1920)
    State.h = mp.get_property_number('osd-height', 1080)
end

local f = io.open("/tmp/nuvio-osc-loaded.txt", "w")
if f then f:write("loaded\n") f:close() end
mp.msg.info("nuvio-osc: script loaded, version 3")

local function draw()
    update_size()
    local w, h = State.w, State.h
    mp.msg.verbose("nuvio-osc: draw w=" .. w .. " h=" .. h .. " visible=" .. tostring(State.visible))
    if w <= 0 or h <= 0 then return end

    local progress = 0
    if State.dur > 0 then
        progress = State.pos / State.dur
    end
    if progress < 0 then progress = 0 end
    if progress > 1 then progress = 1 end

    local bar_x = 25
    local bar_w = w - 50
    local bar_y = h - BOTTOM_PAD

    local s = assdraw.ass_new()

    -- Fullscreen background scrim when visible
    if State.visible then
        -- Top scrim gradient (4 layers)
        for i = 0, 3 do
            local a = math.floor(180 - i * 45)
            local ah = string.format('%X', a):sub(-2)
            s:append('{\\pos(0,0)\\bord0\\shad0\\1c&H000000&\\1a&H' .. ah .. '&}')
            s:append('{\\p1}m 0 0 l ' .. w .. ' 0 ' .. w .. ' ' .. (50 + i * 25) .. ' 0 ' .. (50 + i * 25) .. '{\\p0}')
        end
        -- Bottom scrim gradient (6 layers)
        for i = 0, 5 do
            local a = math.floor(30 + i * 35)
            local ah = string.format('%X', a):sub(-2)
            local y0 = h - BOTTOM_PAD - 80 + i * 15
            s:append('{\\pos(0,' .. y0 .. ')\\bord0\\shad0\\1c&H000000&\\1a&H' .. ah .. '&}')
            s:append('{\\p1}m 0 0 l ' .. w .. ' 0 ' .. w .. ' 20 0 20{\\p0}')
        end
    end

    if State.visible then
        -- Title (top-left)
        if State.title ~= '' then
            s:new_event()
            s:append('{\\pos(25,' .. TOP_PAD .. ')\\bord1\\shad1\\1c&HFFFFFF&\\3c&H000000&\\fs18\\fnSansSerif\\b1}')
            s:esc(State.title)
        end

        -- Seekbar background
        s:new_event()
        s:append('{\\pos(' .. bar_x .. ',' .. bar_y .. ')\\bord0\\shad0\\1c&H333333&\\1a&H40&}')
        s:append('{\\p1}m 0 0 l ' .. bar_w .. ' 0 ' .. bar_w .. ' ' .. BAR_H .. ' 0 ' .. BAR_H .. '{\\p0}')

        -- Seekbar progress
        if progress > 0 then
            local pw = math.floor(bar_w * progress)
            s:new_event()
            s:append('{\\pos(' .. bar_x .. ',' .. bar_y .. ')\\bord0\\shad0\\1c&H2F6FED&\\1a&H00&}')
            s:append('{\\p1}m 0 0 l ' .. pw .. ' 0 ' .. pw .. ' ' .. BAR_H .. ' 0 ' .. BAR_H .. '{\\p0}')
        end

        -- Seekbar handle (circle)
        local hx = bar_x + math.floor(bar_w * progress)
        local hr = 8
        s:new_event()
        s:append('{\\pos(' .. (hx - hr) .. ',' .. (bar_y - hr + BAR_H / 2) .. ')\\bord0\\shad0\\1c&HFFFFFF&\\1a&H00&}')
        s:append('{\\p1}m ' .. hr .. ' 0 b ' .. hr .. ' -' .. hr .. ' 0 -' .. hr .. ' -' .. hr .. ' -' .. hr .. ' -' .. hr .. ' 0 -' .. hr .. ' ' .. hr .. ' 0 ' .. hr .. ' ' .. hr .. ' 0 ' .. hr .. ' ' .. hr .. ' ' .. hr .. ' b ' .. hr .. ' ' .. hr .. ' 0 ' .. hr .. ' -' .. hr .. ' ' .. hr .. ' -' .. hr .. ' ' .. hr .. '{\\p0}')

        -- Time labels
        local time_str = fmt_time(State.pos) .. ' / ' .. fmt_time(State.dur)
        s:new_event()
        s:append('{\\pos(' .. bar_x .. ',' .. (bar_y + BAR_H + 18) .. ')\\bord1\\shad1\\1c&HFFFFFF&\\3c&H000000&\\fs14\\fnSansSerif}')
        s:esc(time_str)

        -- Play/Pause icon (center)
        local icon_y = math.floor(h / 2 - 20)
        if State.paused then
            -- Play triangle
            s:new_event()
            local cx = math.floor(w / 2)
            local sz = 25
            s:append('{\\pos(' .. (cx - sz / 3) .. ',' .. (icon_y - sz) .. ')\\bord2\\shad1\\1c&HFFFFFF&\\3c&H000000&\\fs1\\fnSansSerif}')
            s:append('{\\p1}m 0 0 l ' .. sz .. ' ' .. sz .. ' l 0 ' .. (sz * 2) .. '{\\p0}')
        else
            -- Pause bars
            s:new_event()
            local cx = math.floor(w / 2)
            local bw = 8
            local gap = 12
            local bh = 40
            s:append('{\\pos(' .. (cx - gap - bw) .. ',' .. icon_y .. ')\\bord0\\shad0\\1c&HFFFFFF&\\1a&H00&}')
            s:append('{\\p1}m 0 0 l ' .. bw .. ' 0 ' .. bw .. ' ' .. bh .. ' 0 ' .. bh .. '{\\p0}')
            s:new_event()
            s:append('{\\pos(' .. (cx + gap) .. ',' .. icon_y .. ')\\bord0\\shad0\\1c&HFFFFFF&\\1a&H00&}')
            s:append('{\\p1}m 0 0 l ' .. bw .. ' 0 ' .. bw .. ' ' .. bh .. ' 0 ' .. bh .. '{\\p0}')
        end

        -- Loading spinner
        if State.loading then
            local angle = math.floor((mp.get_time() * 5) % 360)
            local cx, cy = math.floor(w / 2), math.floor(h / 2 + 50)
            local r = 18
            s:new_event()
            s:append('{\\pos(' .. (cx - r) .. ',' .. (cy - r) .. ')\\bord3\\shad0\\1c&HAAAAAA&\\3c&H000000&\\1a&H00&}')
            s:append('{\\p1}m ' .. r .. ' 0 b ' .. r .. ' ' .. r .. ' 0 ' .. r .. ' -' .. r .. ' ' .. r .. ' -' .. r .. ' 0 -' .. r .. ' -' .. r .. ' 0 -' .. r .. ' -' .. r .. '{\\p0}')
        end
    end

    if not overlay then
        overlay = mp.create_osd_overlay('ass-events')
    end
    overlay.data = s.text
    overlay:update()
end

local function show()
    State.visible = true
    draw()
    if hide_timer then hide_timer:kill() end
    hide_timer = mp.add_timeout(VIS_TIMEOUT, function()
        State.visible = false
        draw()
    end)
end

-- Property observers
mp.observe_property('time-pos', 'number', function(_, val)
    State.pos = val or 0
    draw()
end)
mp.observe_property('duration', 'number', function(_, val)
    State.dur = val or 0
    draw()
end)
mp.observe_property('pause', 'bool', function(_, val)
    State.paused = val or false
    draw()
end)
mp.observe_property('idle-active', 'bool', function(_, val)
    State.loading = val or false
    draw()
end)
mp.observe_property('media-title', 'string', function(_, val)
    State.title = val or ''
    draw()
end)

-- Mouse events
mp.register_event('mouse-move', function()
    show()
end)

mp.register_script_message('mouse-click', function(btn)
    if btn == 'left' then
        mp.command('cycle pause')
        show()
    elseif btn == 'right' then
        mp.command('cycle fullscreen')
    end
end)

-- Bind mouse clicks via input-conf is not possible from Lua,
-- so we use a different approach: register key bindings
mp.add_key_binding('MBTN_LEFT', 'osc-click', function()
    mp.command('cycle pause')
    show()
end)

mp.add_key_binding('MBTN_LEFT_DBL', 'osc-dblclick', function()
    mp.command('cycle fullscreen')
    show()
end)

mp.add_key_binding('WHEEL_UP', 'osc-wheel-up', function()
    mp.command('seek 5')
    show()
end)

mp.add_key_binding('WHEEL_DOWN', 'osc-wheel-down', function()
    mp.command('seek -5')
    show()
end)

-- Initial show
show()

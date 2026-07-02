-- Nuvio subtitle panel trigger
-- Opens the Nuvio subtitle selection panel when subtitles change via OSC
local mp = require 'mp'

mp.msg.info("nuvio-subs: loaded OK")
mp.osd_message("nuvio-subs loaded", 2)

local last_sid = mp.get_property('sid')
local file_loaded = false

mp.register_event("file-loaded", function()
    file_loaded = true
    last_sid = mp.get_property('sid')
    mp.msg.info("nuvio-subs: file loaded, last_sid=" .. tostring(last_sid))
end)

mp.register_event("end-file", function()
    file_loaded = false
end)

mp.observe_property('sid', 'string', function(name, new_sid)
    if not file_loaded then
        last_sid = new_sid
        return
    end
    if new_sid ~= last_sid then
        mp.msg.info("nuvio-subs: sid changed: " .. tostring(last_sid) .. " -> " .. tostring(new_sid))
        last_sid = new_sid
        local file = io.open("/tmp/nuvio-subs-ignore", "r")
        if file then
            file:close()
            mp.msg.info("nuvio-subs: ignoring (sentinel present)")
            return
        end
        mp.osd_message("Nuvio: opening subtitle panel")
        mp.command('script-message nuvio-open-subtitle-panel')
    end
end)

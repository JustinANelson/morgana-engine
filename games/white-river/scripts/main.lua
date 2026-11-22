-- White River Online main script
-- Morgana ORPG Engine API Version: 1

-- /season: what the valley's calendar says.
register.command("season", "Ask what season and hour it is in the valley", 0, function(player, args, world)
    local clock = world.clock
    if not clock or not player then
        return true
    end
    world.send_message(player.id, "It is " .. clock.text .. " on day " .. clock.day_of_season .. " of " .. clock.season .. ".", "#7DD3FC")
    return true
end)

-- /ford: a traveller's greeting, heard by the whole realm.
register.command("ford", "Hail the realm from the river road", 0, function(player, args, world)
    local name = player and player.name or "A traveller"
    world.broadcast(name .. " hails the Ford from the river road.", nil, "#7DD3FC")
    return true
end)

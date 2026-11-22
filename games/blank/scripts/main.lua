-- Blank Game Main Script
-- Morgana ORPG Engine API Version: 1

register.command("hello", "Say hello to the realm", 0, function(player, args, world)
    local name = player and player.name or "Adventurer"
    world.broadcast("[" .. name .. "] greets the realm of Blank Game!", nil, "#38BDF8")
    return true
end)

register.action("scout", function(char_id, params, world)
    local player = world.get_player(char_id)
    if player then
        world.floating_text(player.map_id, player.x, player.y, "*scouting*", "action", "#FACC15")
    end
    world.send_message(char_id, "You scout your surroundings in Blank Game.", "#94A3B8")
end)

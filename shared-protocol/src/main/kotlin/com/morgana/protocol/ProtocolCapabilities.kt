package com.morgana.protocol

/**
 * Standard protocol capability identifiers exchanged at login/resume between client and server.
 * Enables graceful forward-compatible feature negotiation without breaking changes.
 */
object ProtocolCapabilities {
    /** Streaming of entities on neighbouring map borders ("soft seams"). */
    const val SOFT_SEAMS = "soft_seams"

    /** Resuming an existing session via single-use resume tokens after connection drops. */
    const val SESSION_RESUME = "session_resume"

    /** Pre-login realm metadata querying (playable classes, description). */
    const val REALM_INFO = "realm_info"

    /**
     * Send-once map cache: the server sends each map (at a given revision) once per connection, and the client rebuilds
     * the snapshot's maps from its cache using [com.morgana.protocol.MapRef]s. Without it, every snapshot carries full maps.
     */
    const val MAP_CACHE = "map_cache"

    /**
     * The client understands the icon, paperdoll, projectile and animation asset kinds in the asset manifest. Without it the
     * server leaves those entries out (an older client could not decode the new kinds) and sends tilesets and sprites only.
     */
    const val ART_V2 = "art_v2"

    /**
     * The client understands plugin messages and plugin-described windows ([ServerPacket.PluginMessage], [ServerPacket.PluginWindow]).
     * The server sends them only to clients that advertise it. These are new packet types that older clients decode as
     * `Unknown` and ignore, so adding them needed no [PROTOCOL_VERSION] bump.
     */
    const val PLUGIN_UI = "plugin_ui"

    /**
     * The client can show a character list after login ([ServerPacket.CharacterList]) and create, choose and delete characters.
     * Without it the server logs the account into its first character, as before. It is *not* in
     * [DEFAULT_CLIENT_CAPABILITIES]: the game client adds it explicitly, so scripts and bots keep the one-step login.
     * These are new packet types that older clients decode as `Unknown`, so no [PROTOCOL_VERSION] bump was needed.
     */
    const val CHARACTER_SELECT = "character_select"

    /**
     * The client tells the server when the player closes a dialogue line that offers no answers, by sending
     * [ClientPacket.SelectDialogueChoice] with a choice index of -1, so an event can show one line after another and run its
     * later steps only once the line was read. For a client without it the server continues such an event at once, as it always
     * did. Not in [DEFAULT_CLIENT_CAPABILITIES]: the game client adds it explicitly. No new packet, so no version bump.
     */
    const val DIALOGUE_FLOW = "dialogue_flow"

    /**
     * The client shows role-play chat styles ([ServerPacket.ChatBroadcast.style]): actions, out-of-character talk, shouts,
     * quiet speech and narration. Without it the server folds the style into the message text ("* Aria waves").
     * Only a new field with a default, which older clients ignore, so no [PROTOCOL_VERSION] bump.
     */
    const val RP_CHAT = "rp_chat"

    /**
     * The server answers `GET <assetBaseUrl>/_batch?p=<path>&p=<path>...` with several art files in one response
     * ([com.morgana.protocol.realms.AssetBundle]). Sent by the server only when it hosts its own art (an operator's CDN
     * has no such endpoint). A client that does not know it fetches one file per request, as before.
     */
    const val ASSET_BATCH = "asset_batch"

    /**
     * The client shows new-player tips ([ServerPacket.Tip]) and can turn them off ([ClientPacket.TipsControl]). The server
     * sends tips only to clients that advertise it. New packet types that older clients decode as `Unknown`, so no
     * [PROTOCOL_VERSION] bump was needed.
     */
    const val TIPS = "tips"

    /**
     * The client draws world objects (walls, chests, totems placed by the `worldState` system). The server then sends the objects of the
     * map in [ServerPacket.MapStateSnapshot.worldObjects] and changes as [ServerPacket.WorldObjectsUpdated]; without it clients are told
     * nothing about them. A new field with a default and a new packet type, so no [PROTOCOL_VERSION] bump.
     */
    const val WORLD_OBJECTS = "world_objects"

    /** Default capability set advertised by standard Morgana clients. */
    val DEFAULT_CLIENT_CAPABILITIES: List<String> = listOf(
        SOFT_SEAMS,
        SESSION_RESUME,
        REALM_INFO,
        MAP_CACHE,
        ART_V2,
        PLUGIN_UI,
        RP_CHAT,
        TIPS,
        WORLD_OBJECTS
    )
}


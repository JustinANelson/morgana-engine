package com.morgana.server.tools

import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.AreaOfInterestConfig
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.PlayerDataBackend
import com.morgana.persistence.ServerConfig
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import com.morgana.server.ClientSession
import com.morgana.server.MorganaWorldCoordinator
import com.morgana.server.OwnerPolicy
import com.morgana.server.PacketLimitSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

enum class StressLayout { SPREAD, CLUMPED }

data class StressOptions(
    val counts: List<Int> = listOf(25, 50, 100, 200, 400),
    val ticksPerStage: Int = 400,
    val layout: StressLayout = StressLayout.SPREAD,
    val aoi: Boolean = false,
    val sqlite: Boolean = false,
    val gameDir: Path? = null
)

data class StressStageResult(
    val players: Int,
    val avgMs: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val overBudgetTicks: Int,
    val outboundPerTick: Long
) {
    val status: String
        get() = when {
            avgMs >= 50.0 -> "OVER"
            avgMs >= 25.0 || p95Ms >= 50.0 -> "WARN"
            else -> "OK"
        }
}

private class StressSession(
    override val sessionId: String,
    private val outboundCounter: AtomicLong
) : ClientSession {
    override var characterId: String? = null
    override val isEncrypted: Boolean get() = true
    override fun send(packet: ServerPacket) {
        outboundCounter.incrementAndGet()
    }
    override fun close(reason: String) {}
}

fun parseStressArgs(args: Array<String>): StressOptions {
    var counts = listOf(25, 50, 100, 200, 400)
    var ticks = 400
    var layout = StressLayout.SPREAD
    var aoi = false
    var sqlite = false
    var gameDir: Path? = null

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--counts" -> {
                val raw = args.getOrNull(++i) ?: error("--counts requires a comma-separated list, e.g. 25,50,100")
                counts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
                require(counts.isNotEmpty()) { "--counts must contain at least one positive integer" }
            }
            "--ticks" -> {
                val raw = args.getOrNull(++i) ?: error("--ticks requires a positive integer")
                ticks = raw.toIntOrNull()?.takeIf { it > 0 } ?: error("Invalid --ticks: $raw")
            }
            "--layout" -> {
                val raw = args.getOrNull(++i)?.lowercase(Locale.ROOT) ?: error("--layout requires spread or clumped")
                layout = when (raw) {
                    "spread" -> StressLayout.SPREAD
                    "clumped" -> StressLayout.CLUMPED
                    else -> error("Unknown --layout '$raw' (expected spread or clumped)")
                }
            }
            "--aoi" -> aoi = true
            "--sqlite" -> sqlite = true
            "--game" -> {
                val raw = args.getOrNull(++i) ?: error("--game requires a directory path")
                gameDir = Path.of(raw)
            }
            else -> error("Unknown stress test flag: $arg")
        }
        i++
    }
    return StressOptions(counts, ticks, layout, aoi, sqlite, gameDir)
}

fun runStressStage(playerCount: Int, options: StressOptions, tempRoot: Path): StressStageResult {
    val stageDir = tempRoot.resolve("stage-$playerCount")
    Files.createDirectories(stageDir)

    options.gameDir?.let { src ->
        copyGameContent(src, stageDir)
    }

    var cfg = ServerConfig()
    if (options.sqlite) cfg = cfg.withPlayerData(PlayerDataBackend.SQLITE)
    if (options.aoi) cfg = cfg.copy(areaOfInterest = AreaOfInterestConfig(enabled = true))
    ServerConfig.save(stageDir, cfg)

    val dataStore = MorganaDataStore(stageDir)
    if (options.gameDir == null) {
        GameScaffolder.seedIfEmpty(dataStore.world)
        // Add extra maps for spread layout when running against the blank seed.
        if (options.layout == StressLayout.SPREAD) {
            val baseMap = dataStore.world.maps.findById(1)
            if (baseMap != null) {
                for (id in 2..4) {
                    if (dataStore.world.maps.findById(id) == null) {
                        dataStore.world.maps.save(id, baseMap.copy(id = id, name = "Zone $id"))
                    }
                }
            }
        }
    }

    val coordinator = MorganaWorldCoordinator(
        dataStore = dataStore,
        ownerPolicy = OwnerPolicy(stageDir) { null },
        packetLimits = PacketLimitSettings(enabled = false)
    )

    return try {
        val outbound = AtomicLong(0L)
        val sessions = ArrayList<StressSession>(playerCount)
        val mapIds = coordinator.simulation.maps.keys.filter { it < 1_000_000 }.sorted().ifEmpty { listOf(1) }

        for (idx in 0 until playerCount) {
            val session = StressSession("stress-$idx", outbound)
            coordinator.onSessionConnected(session)
            coordinator.handleClientPacket(
                session,
                ClientPacket.Authenticate(
                    username = "Bot$idx",
                    password = "pw",
                    registerIfNew = true,
                    requestedClassId = 1,
                    protocolVersion = PROTOCOL_VERSION
                )
            )
            val charId = session.characterId ?: error("Bot$idx failed to authenticate")
            if (options.layout == StressLayout.SPREAD) {
                val targetMapId = mapIds[idx % mapIds.size]
                val targetMap = coordinator.simulation.maps.getValue(targetMapId)
                val player = coordinator.simulation.players.getValue(charId)
                val x = (2 + (idx * 3) % (targetMap.width - 4).coerceAtLeast(1)).coerceIn(0, targetMap.width - 1)
                val y = (2 + (idx * 5) % (targetMap.height - 4).coerceAtLeast(1)).coerceIn(0, targetMap.height - 1)
                coordinator.simulation.players[charId] = player.copy(
                    position = WorldPosition.fromTile(targetMapId, x, y, Direction.DOWN)
                )
            }
            sessions.add(session)
        }

        // Warm up 10 ticks and reset outbound counter before timing.
        repeat(10) { coordinator.stepServerTick(50) }
        outbound.set(0L)

        val dirs = arrayOf(Direction.LEFT, Direction.UP, Direction.RIGHT, Direction.DOWN)
        val tickNanos = LongArray(options.ticksPerStage)
        var overBudget = 0

        for (t in 0 until options.ticksPerStage) {
            if (t % 2 == 0) {
                val dir = dirs[(t / 2) % dirs.size]
                for (s in sessions) {
                    coordinator.handleClientPacket(s, ClientPacket.MoveInput(dir))
                }
            }
            if (t % 8 == 0) {
                for (s in sessions) {
                    coordinator.handleClientPacket(s, ClientPacket.Attack)
                }
            }
            val start = System.nanoTime()
            coordinator.stepServerTick(50)
            val elapsed = System.nanoTime() - start
            tickNanos[t] = elapsed
            if (elapsed >= 50_000_000L) overBudget++
        }

        val sorted = tickNanos.sortedArray()
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        val avgMs = tickNanos.average() / 1_000_000.0
        val p95Ms = sorted[p95Index] / 1_000_000.0
        val maxMs = sorted.last() / 1_000_000.0
        val outboundPerTick = outbound.get() / options.ticksPerStage.coerceAtLeast(1)

        StressStageResult(playerCount, avgMs, p95Ms, maxMs, overBudget, outboundPerTick)
    } finally {
        coordinator.close()
        dataStore.close()
    }
}

private fun copyGameContent(src: Path, dst: Path) {
    val gameJson = src.resolve("game.json")
    if (Files.isRegularFile(gameJson)) {
        Files.copy(gameJson, dst.resolve("game.json"), StandardCopyOption.REPLACE_EXISTING)
    }
    for (folder in listOf("data", "scripts")) {
        val srcFolder = src.resolve(folder)
        if (!Files.isDirectory(srcFolder)) continue
        val dstFolder = dst.resolve(folder)
        Files.walk(srcFolder).use { stream ->
            stream.forEach { path ->
                val rel = srcFolder.relativize(path)
                val target = dstFolder.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    val options = parseStressArgs(args)
    val tempRoot = Files.createTempDirectory("morgana-stress-")
    try {
        println(
            "Morgana Server Stress Test | layout=${options.layout.name.lowercase(Locale.ROOT)} " +
                "| ticks/stage=${options.ticksPerStage} | aoi=${options.aoi} | sqlite=${options.sqlite}"
        )
        println(String.format(Locale.US, "%-8s | %-9s | %-9s | %-9s | %-6s | %-13s | %s",
            "Players", "Avg (ms)", "P95 (ms)", "Max (ms)", ">50ms", "Outbound/tick", "Status"))
        println("-".repeat(78))
        for (count in options.counts) {
            val res = runStressStage(count, options, tempRoot)
            println(
                String.format(
                    Locale.US,
                    "%-8d | %9.2f | %9.2f | %9.2f | %6d | %13d | %s",
                    res.players, res.avgMs, res.p95Ms, res.maxMs, res.overBudgetTicks, res.outboundPerTick, res.status
                )
            )
        }
    } finally {
        tempRoot.toFile().deleteRecursively()
    }
}

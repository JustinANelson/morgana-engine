package com.morgana.client.probe

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.kotcrab.vis.ui.widget.VisTextField
import com.morgana.client.editor.dock.PanelInfo
import com.morgana.client.state.ClientWorldState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A read-only description of what the client shows, as JSON, for the automated browser smoke test (`smoke-test/`).
 * The game draws everything on one canvas, so a test cannot find a button or read the player's position from the page;
 * this says where every visible text button is on screen, which windows are open, whether the player is in the world and
 * where, and the state of the F1 editor. Nothing here changes the game, and the browser build exposes it only as
 * `window.__morganaProbe()`.
 *
 * Coordinates are in the game's own screen pixels (origin top-left, the size given by `screenWidth` and `screenHeight`),
 * which the test scales to the page's size.
 */
object GameProbe {

    fun snapshot(
        stage: Stage?,
        worldState: ClientWorldState,
        editorOpen: Boolean,
        panels: List<PanelInfo>,
        finderOpen: Boolean
    ): String {
        if (stage == null) return buildJsonObject { put("ready", false) }.toString()
        val character = worldState.localCharacter
        val map = worldState.activeMap
        val editor = worldState.editor
        return buildJsonObject {
            put("ready", true)
            put("screenWidth", Gdx.graphics.width)
            put("screenHeight", Gdx.graphics.height)
            put("inWorld", character != null && map != null)
            put("mapId", map?.id ?: -1)
            put("mapName", map?.name.orEmpty())
            if (character != null) {
                put("playerTileX", character.position.tileCoord.x)
                put("playerTileY", character.position.tileCoord.y)
                put("playerPixelX", character.position.pixelX)
                put("playerPixelY", character.position.pixelY)
            }
            put("editorOpen", editorOpen)
            put("panelFinderOpen", finderOpen)
            put("editorTool", editor.tool.name)
            put("editorDirty", editor.isDirty)
            put("editorUndoDepth", editor.undoDepth)
            put("panels", buildJsonArray {
                for (p in panels) add(buildJsonObject { put("id", p.id); put("title", p.title); put("state", p.state.name) })
            })
            put("windows", windows(stage))
            put("buttons", buttons(stage))
            put("fields", fields(stage))
            put("overflows", overflows(stage))
            put("chat", buildJsonArray {
                for (m in worldState.chatHistory.takeLast(CHAT_LINES)) add(JsonPrimitive("${m.senderName}: ${m.message}"))
            })
        }.toString()
    }

    private fun windows(stage: Stage): JsonArray = buildJsonArray {
        walk(stage.root) { actor -> if (actor is Window && actor !== stage.root) add(JsonPrimitive(actor.titleLabel.text.toString())) }
    }

    private fun buttons(stage: Stage): JsonArray = buildJsonArray {
        walk(stage.root) { actor ->
            if (actor is TextButton && actor.width > 0f && actor.height > 0f) {
                val centre = stage.stageToScreenCoordinates(actor.localToStageCoordinates(Vector2(actor.width / 2f, actor.height / 2f)))
                add(buildJsonObject {
                    put("text", actor.text.toString())
                    put("x", centre.x)
                    put("y", centre.y)
                    put("disabled", actor.isDisabled)
                })
            }
        }
    }

    /** Visible text fields, found by their hint text (the grey text shown while empty); passwords are never reported. */
    private fun fields(stage: Stage): JsonArray = buildJsonArray {
        walk(stage.root) { actor ->
            val (hint, text) = when (actor) {
                is TextField -> actor.messageText.orEmpty() to (if (actor.isPasswordMode) "" else actor.text)
                is VisTextField -> actor.messageText.orEmpty() to (if (actor.isPasswordMode) "" else actor.text)
                else -> return@walk
            }
            if (actor.width > 0f && actor.height > 0f) {
                val centre = stage.stageToScreenCoordinates(actor.localToStageCoordinates(Vector2(actor.width / 2f, actor.height / 2f)))
                add(buildJsonObject {
                    put("hint", hint)
                    put("text", text)
                    put("x", centre.x)
                    put("y", centre.y)
                })
            }
        }
    }

    /** Reports visible labels whose text exceeds their laid-out bounds, and windows that extend off-screen. */
    private fun overflows(stage: Stage): JsonArray = buildJsonArray {
        val tol = 2f
        walk(stage.root) { actor ->
            if (actor is Window && actor !== stage.root) {
                val offScreen = actor.x < -tol || actor.y < -tol ||
                    actor.x + actor.width > stage.width + tol ||
                    actor.y + actor.height > stage.height + tol
                if (offScreen) {
                    add(buildJsonObject {
                        put("kind", "window-offscreen")
                        put("window", actor.titleLabel.text.toString())
                        put("x", actor.x)
                        put("y", actor.y)
                        put("w", actor.width)
                        put("h", actor.height)
                        put("stageW", stage.width)
                        put("stageH", stage.height)
                    })
                }
            } else if (actor is Label && actor.width > 0f && actor.height > 0f) {
                val text = actor.text?.toString().orEmpty().trim()
                if (text.isEmpty() || isInsideSelectBox(actor) || isHorizontallyScrollable(actor)) return@walk
                val clippedW = !actor.wrap && actor.prefWidth > actor.width + tol
                val clippedH = actor.wrap && actor.prefHeight > actor.height + tol
                val win = enclosingWindow(actor)
                val outsideWin = if (win != null && !isInsideScrollPane(actor)) {
                    val pos = actor.localToStageCoordinates(Vector2(0f, 0f))
                    pos.x < win.x - tol || pos.x + actor.width > win.x + win.width + tol
                } else false
                if (clippedW || clippedH || outsideWin) {
                    add(buildJsonObject {
                        put("kind", when {
                            outsideWin -> "outside-window"
                            clippedW -> "label-width"
                            else -> "label-height"
                        })
                        put("window", win?.titleLabel?.text?.toString().orEmpty())
                        put("text", text.take(80))
                        put("w", actor.width)
                        put("prefW", actor.prefWidth)
                        put("h", actor.height)
                        put("prefH", actor.prefHeight)
                    })
                }
            }
        }
    }

    private fun isInsideSelectBox(actor: Actor): Boolean {
        var cur: Actor? = actor.parent
        while (cur != null) {
            if (cur is SelectBox<*>) return true
            cur = cur.parent
        }
        return false
    }

    private fun isHorizontallyScrollable(actor: Actor): Boolean {
        var cur: Actor? = actor.parent
        while (cur != null) {
            if (cur is ScrollPane && !cur.isScrollingDisabledX) return true
            cur = cur.parent
        }
        return false
    }

    private fun isInsideScrollPane(actor: Actor): Boolean {
        var cur: Actor? = actor.parent
        while (cur != null) {
            if (cur is ScrollPane) return true
            cur = cur.parent
        }
        return false
    }

    private fun enclosingWindow(actor: Actor): Window? {
        var cur: Actor? = actor.parent
        while (cur != null) {
            if (cur is Window) return cur
            cur = cur.parent
        }
        return null
    }

    /** Visits every actor that is actually shown: an invisible actor hides everything under it. */
    private fun walk(actor: Actor, visit: (Actor) -> Unit) {
        if (!actor.isVisible) return
        visit(actor)
        if (actor is Group) for (child in actor.children) walk(child, visit)
    }

    private const val CHAT_LINES = 6
}

package com.morgana.client.editor.forms

import com.morgana.domain.content.AnimationDefinition
import com.morgana.protocol.WorldContentCatalogDto

/** The animation form: which sprite sheet plays, how many frames, how fast, and an optional sound. */
object AnimationForm : ContentForm<AnimationDefinition> {
    override val title = "Animations"
    override val noun = "animation"
    override val firstId = 1

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Animation", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("sheet", "Sprite sheet", FormFieldType.INT, "1", min = 1, hint = "The number of the sprite sheet the frames come from."),
            FormField("frames", "Number of frames", FormFieldType.INT, "5", min = 1, max = 64),
            FormField("duration", "Time per frame (milliseconds)", FormFieldType.INT, "80", min = 10, max = 2000, hint = "1000 milliseconds is one second. Smaller is faster."),
            FormField("sound", "Sound effect", FormFieldType.TEXT, hint = "Optional. The id of the sound to play with it."),
            FormField("above", "Draw over characters", FormFieldType.BOOL, "true")
        ))
    )

    override fun idOf(definition: AnimationDefinition) = definition.id
    override fun nameOf(definition: AnimationDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = AnimationDefinition(id = id, name = "New animation $id")
    override fun all(catalog: WorldContentCatalogDto) = catalog.animations.sortedBy { it.id }

    override fun valuesOf(definition: AnimationDefinition, context: FormContext): Map<String, String> = mapOf(
        "name" to definition.name, "sheet" to definition.spriteSheetId.toString(), "frames" to definition.frameCount.toString(),
        "duration" to definition.frameDurationMs.toString(), "sound" to definition.soundId, "above" to definition.renderAboveEntities.toString()
    )

    override fun apply(definition: AnimationDefinition, values: Map<String, String>, context: FormContext) = definition.copy(
        name = FormRules.text(values, "name", definition.name).ifBlank { definition.name },
        spriteSheetId = FormRules.int(values, "sheet", definition.spriteSheetId).coerceAtLeast(1),
        frameCount = FormRules.int(values, "frames", definition.frameCount).coerceIn(1, 64),
        frameDurationMs = FormRules.int(values, "duration", definition.frameDurationMs).coerceIn(10, 2000),
        soundId = FormRules.text(values, "sound", definition.soundId),
        renderAboveEntities = FormRules.bool(values, "above", definition.renderAboveEntities)
    )
}

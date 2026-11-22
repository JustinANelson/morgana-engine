---
type: Playbook
title: "Add an Event Command"
description: "Steps to add a new page-event command."
tags: [playbook, events]
code: ["core-domain/src/main/kotlin/com/morgana/domain/event/QuestAndEventModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EventSystem.kt", "client-core/src/main/kotlin/com/morgana/client/editor/events/EventForms.kt"]
symbols: ["EventCommand", "runEventSteps", "InvokeKotlinHook", "EventForms"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Add an Event Command

1. Add a `@Serializable @SerialName("snake_case")` subtype of `EventCommand` ([events and quests](/systems/events_quests.md)).
2. Implement it in the `when` of `runEventSteps` (`EventSystem.kt`); emit events, stay deterministic ([determinism](/domain/determinism_and_rng.md)).
3. Validate its references in `Collector.commands` ([validation rules](/content/validation_rules.md)).
4. `exportContentSchemas` (the `oneOf` updates automatically); add a simulation test and a validator test.
5. Update the command list on the events page.
6. Give it to the **Events panel** in `EventForms` (`client-core`): an `EventCommandKind` entry (label, group, explanation), and its branch in `kindOf`, `fields`, `valuesOf`, `apply`, `create`, `summary` and, for anything it points at, `commandProblems`. The compiler lists every `when` that needs it, and `EventFormsTest` fails until the new kind is in its list ([F1 editor](/client/f1_editor.md)).

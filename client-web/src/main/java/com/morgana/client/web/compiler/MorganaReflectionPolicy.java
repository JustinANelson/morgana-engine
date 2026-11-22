package com.morgana.client.web.compiler;

import java.util.Set;
import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

/**
 * What TeaVM must keep reachable by name for the browser build. Scene2D {@code Skin} JSON (VisUI's skin) creates its
 * style objects, colors and fonts by class name and then fills their fields reflectively; without these grants the
 * login screen cannot even be built.
 */
public class MorganaReflectionPolicy extends SimpleReflectionPolicy {
    private static final String[] PREFIXES = {
            "com.kotcrab.vis.ui.",
            "com.badlogic.gdx.scenes.scene2d.",
            "com.badlogic.gdx.graphics.g2d."
    };

    private static final Set<String> EXACT = Set.of(
            "com.badlogic.gdx.graphics.Color",
            "com.badlogic.gdx.utils.Array",
            "com.badlogic.gdx.utils.ObjectMap",
            "com.badlogic.gdx.utils.ObjectSet",
            "com.badlogic.gdx.utils.IntMap"
    );

    private static boolean wanted(String name) {
        if (EXACT.contains(name)) return true;
        for (String prefix : PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void setup() {
        selectClasses(type -> wanted(type.name()))
                .foundByName()
                .reflectableMembers(member -> true);
    }
}

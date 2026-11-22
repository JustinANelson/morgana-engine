# Vendored third-party dependencies

Files here are checked into the repository on purpose, so a build never depends on a third-party host being up, and so the exact bytes
we run are pinned. `checksums.sha256` lists the SHA-256 of every file; the `verifyThirdParty` Gradle task checks them and the modules
that use these files will not compile if one differs.

## Luak 26.11 (`net.blueva:luak-core-jvm`)

| | |
| :--- | :--- |
| What | Kotlin Multiplatform implementation of Lua 5.5.1; we use only the JVM core artifact (no `luajava`, `io.popen`, `os.execute` or JIT, which `luak-jvm` would add) |
| Source | https://github.com/BluevaDevelopment/Luak, tag `v26.11`, commit `1d9d13a6130f9e30344feb92c23ffca4afdd7dda` |
| Downloaded from | https://repo.blueva.net/releases/net/blueva/luak-core-jvm/26.11/ on 2026-11-08 |
| Verified | the jar's SHA-256 equals the `.sha256` the repository publishes |
| Files | `.jar`, `.pom`, `.module` (Gradle metadata), `-sources.jar` (kept so a fork or an audit works offline) |
| Runtime dependency | `org.jetbrains.kotlin:kotlin-stdlib:2.4.10` (from Maven Central, version pinned by the Kotlin plugin) |
| License | MIT, text in `licenses/Luak-LICENSE.txt` (its copyright line names LuaJ, which suggests the code descends from LuaJ; the README does not say) |

How it is wired: the root `build.gradle.kts` declares this folder as a Maven repository that is the only source for the `net.blueva`
group (`exclusiveContent`), and only `:script-luak` depends on it. Luak types must stay inside that module (see
[ADR 0013](../wiki/decisions/0013_engine_game_split_and_lua_scripting.md)).

## Updating a vendored dependency

1. Download the new `.jar`, `.pom`, `.module` and `-sources.jar` and check the jar against the publisher's checksum.
2. Put them in a new `maven/.../<version>/` folder and update `licenses/` if the license text changed.
3. Regenerate `checksums.sha256` (`sha256sum` each file, path relative to this folder) and update the table above.
4. Update the version in `script-luak/build.gradle.kts`, run `.\gradlew.bat check`, and re-run the sandbox tests: the Luak adapter's tests are the
   sandbox specification, so a version bump that changes behavior shows up there.
5. Delete the old version folder once nothing uses it.

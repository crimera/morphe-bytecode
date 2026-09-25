# morphe-bytecode

Typed Dalvik bytecode emission for [Morphe](https://github.com/MorpheApp) patches.

Replaces string-template smali injection with a typed emitter: opcodes and register widths
are chosen from operand types, branch-target relocation is a policy the call site states,
and every failure is a patch-time `PatchException` instead of a device verifier error.

```kotlin
method.insertHook(index, relocateBranchTargets = true) {
    constString(v1, SETTING_KEY)
    invokeStatic(settingsLookup, v1)
    moveResult(v1)
    ifNez(v1, label("skip"))
    iget(receiver, v0, enabledField)
    invokeStatic(applyOverride, receiver, v0)
    label("skip")
    returnVoid()
}
```

## Artifacts

| | |
|---|---|
| Coordinates | `crimera:morphe-bytecode:0.1.0` |
| Registry | GitHub Packages, `https://maven.pkg.github.com/crimera/morphe-bytecode` |
| License | GPL-3.0-or-later, see `LICENSE` and `NOTICE` |

GitHub Packages requires credentials even for public packages. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/crimera/morphe-bytecode")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN") // needs read:packages
            }
        }
    }
}
```

```kotlin
dependencies {
    implementation("crimera:morphe-bytecode:0.1.0")
}
```

For local development against a checkout, a Gradle composite build substitutes the published
artifact automatically:

```kotlin
// settings.gradle.kts of the consuming build
includeBuild("../morphe-bytecode")
```

## API

| Symbol | Purpose |
|---|---|
| `MutableMethod.insertHook(index, excludedRegisters, relocateBranchTargets) { }` | Insert a typed block in front of an instruction. `relocateBranchTargets` is required whenever the instruction carries branch labels. |
| `Block` | Emitter receiver: `move`, `iget`/`iput`, `sget`/`sput`, `moveResult`, `constInt`/`constLong`/`constString`/`constClass`, `newInstance`, `newArray`, `checkCast`, `instanceOf`, `invoke*`, `if*`, `goto`, `label`, `return*`. |
| `Target` | Branch destination: `Target.Local("name")` or `Target.Original`. |
| `RegisterLimit` | `FOUR_BIT` / `BYTE` / `SHORT` bounds for `scratchRegister`. |
| `methodReference` / `fieldReference` | Parse `Lowner;->name(args)ret` and `Lowner;->name:type` into dexlib2 references, failing on malformed input. |

Guarantees: scratch registers come from Morphe's branch-following liveness search and fail
closed when the request cannot be encoded; try-block boundaries, exception handlers and
switch/fill-array-data payload labels are never relocated; an inserted `move-result` without
its producing invoke is rejected.

## Build

```bash
./gradlew build            # compile + tests
./gradlew publishToMavenLocal
```

## Publish

Tag `v0.1.0` (or run the workflow manually) — `.github/workflows/publish.yml` publishes to
GitHub Packages with the tag as the version.

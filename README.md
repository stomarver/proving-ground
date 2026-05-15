# Shadow Scene (LWJGL 3.4.1 + Java 25)

## IntelliJ startup/sync model (RubyDung-style pragmatic bootstrap)

Чтобы избежать ошибки вида `Unsupported class file major version 69` на этапе **Gradle Sync**:

1. В IntelliJ выставь **Gradle JVM = JDK 24** (или JDK 23/24).\n2. **Не задавай `org.gradle.java.home` в проекте жестким путём** — это ломается на других машинах.\n   Если нужно, задавай его только локально в `~/.gradle/gradle.properties`.
3. В проекте оставь **Java toolchain = 25** (уже настроено в `build.gradle`).
4. `Main` — отдельная точка входа, которая запускает `ShadowScene`.

Такой split-подход разделяет:
- JVM для работы Gradle/Groovy (более совместимая),
- JVM для компиляции проекта (Java 25).

## Run

```bash
gradle run
```

## Notes

- LWJGL version: `3.4.1`
- Java toolchain: `25`
- Render modes: `F` toggles `STENCIL_VOLUMES` / `CSM` live

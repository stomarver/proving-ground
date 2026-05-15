# Shadow Scene (LWJGL 3.4.1 + Java 25)

## Как автоматически скачать Gradle (без хранения бинарников в репозитории)

Ты можешь не хранить `gradle-wrapper.jar` в git и генерировать wrapper локально:

```bash
gradle wrapper --gradle-version 9.1.0 --distribution-type bin --no-validate-url
```

После этого Gradle автоматически скачает нужный дистрибутив при первом запуске:

```bash
./gradlew help
```

## IntelliJ IDEA

1. Open as Gradle project.
2. В настройках Gradle включи **Use Gradle from: 'gradle-wrapper.properties'**.
3. Gradle JVM: JDK 25.

## Notes

- LWJGL version: `3.4.1`
- Java toolchain: `25`
- `Main` — отдельная точка входа, вызывает `ShadowScene`.
- Клавиша `F` переключает режимы теней `STENCIL_VOLUMES` / `CSM` без перезапуска.

## Если снова видишь `Unsupported class file major version 69`

Это почти всегда значит, что IDEA запустила **не wrapper 9.1.0**, а старый локальный Gradle (например 8.14).

Сделай так:

```bash
# 1) остановить старые демоны
gradle --stop

# 2) сгенерировать wrapper заново (если нет jar)
gradle wrapper --gradle-version 9.1.0 --distribution-type bin --no-validate-url

# 3) проверить, что используется именно 9.1.0
./gradlew --version
```

В IntelliJ:
1. **Settings → Build Tools → Gradle**
2. **Use Gradle from: Gradle Wrapper**
3. **Gradle JVM: JDK 25**
4. Нажми **Reload All Gradle Projects**

# Сборка дистрибутивов MSStudio

[English version](MSS_BUILD.md)

В этой инструкции команды запускаются из корня репозитория. Она описывает сборку дистрибутивов для Linux и Windows.
Цель сборки использует Bazel для компиляции и упаковки продукта. Это не запуск среды разработки.

## Требования

- Полная копия репозитория MSStudio, включая подмодули.
- Доступ в интернет для первой сборки и загрузки отсутствующих зависимостей.
- Свободное место для Bazel, кэшей, временных файлов и результатов сборки.
- В Linux: Bash, Git, `tar` и `curl` или `wget`.
- Для AppImage: Linux, `curl` и поддержка запуска AppImage. Может потребоваться FUSE.
- В Windows: Git, Windows PowerShell и командная строка или PowerShell.

Запускайте команды из корня репозитория. Скрипт `bazel.cmd` загрузит нужную версию Bazelisk при необходимости.
Система сборки также загрузит инструменты и JetBrains Runtime, если их нет в кэше.

По умолчанию результаты находятся в `out/msstudio/artifacts/`. Следующая сборка может заменить или удалить предыдущие результаты.
Перед новой сборкой скопируйте файлы, которые хотите сохранить.

## Дистрибутив для Linux

В Linux соберите архив для x86-64 со встроенной средой выполнения:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=linux \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none
```

Архив находится в `out/msstudio/artifacts/MSStudio-<build>.tar.gz`. Текущий номер сборки — `271.SNAPSHOT`.
В архиве находится папка `MSStudio/`. Распакуйте её и запустите `MSStudio/bin/msstudio.sh`.

Для ARM64 замените `x64` на `aarch64`. Если не указать `intellij.build.target.arch`, сборка создаст файлы для всех поддерживаемых архитектур.
Имя архива ARM64 заканчивается на `-aarch64.tar.gz`.

### AppImage

Сначала соберите архив Linux для той же архитектуры, что и машина для упаковки. Затем выполните:

```bash
./msstudio-appimage.sh out/msstudio/artifacts/MSStudio-271.SNAPSHOT.tar.gz
```

Измените имя архива, если номер сборки или архитектура отличаются. Всегда указывайте путь к архиву явно.
Иначе скрипт выберет самый новый подходящий архив. Он может быть предназначен для другой архитектуры.

Результат находится в `out/MSStudio-<дата>-<архитектура>.AppImage`. Скрипт использует архитектуру текущей машины для имени и среды AppImage.
Он загружает `appimagetool` и при необходимости среду AppImage. При каждом запуске скрипт заменяет `out/appimage-work`.

Чтобы обновить приложение через Gear Lever, замените установленный AppImage новым файлом через Gear Lever или привычным способом.
После обновления перезапустите MSStudio.

## Дистрибутив для Windows

Цель Windows можно запустить в Linux. Сборка загружает среду выполнения Windows и использует NSIS для текущей системы.
Сборку Windows и установщик в этом репозитории пока не проверяли. Перед выпуском проверьте их в Windows.

В Linux соберите ZIP для x86-64 и запустите сборку установщика EXE:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=windows \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none
```

В Windows выполните ту же цель в PowerShell:

```powershell
.\msstudio-installers.cmd '-Dintellij.build.target.os=windows' '-Dintellij.build.target.arch=x64' '-Dintellij.build.non.bundled.plugin.dirs.to.include=none'
```

Ищите `MSStudio-<build>.win.zip` и `MSStudio-<build>.exe` в `out/msstudio/artifacts/`.
ZIP содержит среду выполнения. Распакуйте его и запустите IDE из папки `bin`.
Установщик Windows пока наследует некоторые имена и настройки IntelliJ IDEA. Перед выпуском проверьте экраны установщика и путь установки.

Для ARM64 замените `x64` на `aarch64`. Проверьте результаты на Windows ARM64.
Такие файлы имеют суффикс `-aarch64`.

Если шаг NSIS завершится ошибкой, в Linux можно собрать только ZIP:

```bash
./msstudio-installers.cmd \
  -Dintellij.build.target.os=windows \
  -Dintellij.build.target.arch=x64 \
  -Dintellij.build.non.bundled.plugin.dirs.to.include=none \
  -Dintellij.build.skip.build.steps=windows_exe_installer
```

В Windows добавьте `'-Dintellij.build.skip.build.steps=windows_exe_installer'` к команде PowerShell.
Пропуск шага не исправляет установщик. Перед выпуском EXE изучите ошибку NSIS.

## Параметры сборки и проверка

- `intellij.build.non.bundled.plugin.dirs.to.include=none` пропускает публикацию отдельных плагинов. Встроенные плагины остаются в сборке.
- Сборка отдельно загружает и включает плагин HOCON. Она меняет его `until-build` на `271.*`.
  Это не гарантирует совместимость с текущей версией IDE. Проверьте плагин после запуска.
- Цель сборки пропускает подпись Windows. Поэтому файлы Windows и установщик не подписаны.
- Успешная сборка не гарантирует запуск дистрибутива. Запустите каждый результат в целевой системе и на целевой архитектуре.
  Проверьте запуск, панели инструментов, плагин HOCON и установщик или архив.
- При ошибке проверьте вывод сборки и `out/msstudio/log/`. Перед повтором проверьте сеть и свободное место.
- Не запускайте одновременно сборки с одной папкой результатов.

Инструкция не описывает сборку для macOS, подпись релиза и публикацию. Эти шаги требуют отдельной проверки.

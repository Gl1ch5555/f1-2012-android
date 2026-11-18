# F1 2012 Android (ARM64)

Нативный порт игры **F1 2012** (Xbox 360, Title ID: `434D0847`, движок **EGO 2.0**) для **Android (`arm64-v8a`)** на базе статической рекомпиляции PowerPC → C++ с помощью **ReXGlue SDK**.

> **Статус: активная разработка (`v0.5.12-alpha`).** Игра полностью рекомпилируется из `default.xex` в нативный ARM64 Release (`-O3 + ThinLTO`), запускается на Android, проходит меню, гараж и выезжает на трассу. Архитектура Android-обвязки и Vulkan/Adrenotools-интеграции основана на [nfsmw-android](https://github.com/codepdbh/nfsmw-android), [rexglue-sdk](https://github.com/rexglue/rexglue-sdk) и наработках [fh2-recompiled](https://github.com/Gabrick75/fh2-recompiled).

---

## Как продвигается портирование

### Что уже работает
- **Полная рекомпиляция CPU (`434D0847`):** `default.xex` транслируется в нативный C++23 (`libmain.so` ~71 МБ, Release `-O3 + ThinLTO`); закрыты пропуски непрямых вызовов (`overrides.toml` + расширенный поиск указателей в `.rdata`/`.data` и `lis+addi/ori`).
- **Запуск и геймплей:** стабильный вход в меню, пит-гараж, загрузка трассы и выезд в гонку с рабочим звуком, тач-управлением (русская локализация кнопок, настраиваемая раскладка) и поддержкой геймпадов.
- **Лаунчер F1 2012 Paddock:** тёмный карбоновый UI с анимированным фоном, вкладками **Главная / Графика / Система**, встроенным 4-поточным загрузчиком с докачкой, нуль-копийной распаковкой `.zip`/`.7z`/`.iso` на устройстве (`extract-xiso`), менеджером драйверов `libadrenotools` (Turnip / Qualcomm) и сбором диагностического ZIP.
- **Ключевые фиксы SDK / Vulkan:**
  - Починен постоянный кэш шейдеров и пайплайнов на Android (`Seek(0, SEEK_SET)` после `fopen("a+b")` под `bionic`).
  - Добавлен безопасный срез MSAA (`gpu_cap_msaa`): отключает MSAA на основной сцене, сохраняя 4x MSAA EDRAM-очистки для карт теней и depth pre-pass.
  - Исправлен `exp_adjust` в `SpirvShaderTranslator` (чтение из `word 3` вместо `LOD bias` в `word 4` — причина взрывного пересвета блума и чёрных сцен на трассе).
  - Настроены дефолты под EGO 2.0 (`readback_resolve=some`, `gamma_render_target_as_unorm16=false`, `vulkan_dynamic_rendering=false`).

### В работе
- Тонкая доводка рендера EGO 2.0 на трассе (баланс HDR/блума, детализация шин и теней на Adreno/Turnip).
- Оптимизация производительности (FPS) и проверка стабильности длинных гонок / сохранений.

## Архитектура

- **Статическая рекомпиляция (PPC → C++):** `rexglue codegen` транслирует исполняемый файл Xbox 360 (`default.xex`) в переносимый C++23-код (`app/src/generated/default/`).
- **Рантайм и GPU-бэкенд:** `sdk/` — адаптированный под Android `ReXGlue SDK` (`rexruntime` + `rexgpu-xenos` на **Vulkan 1.1+**).
- **Android-оболочка (`android/`):**
  - **SDL3** (`SDLActivity` + `libmain.so`) для окна, аудио и ввода.
  - **libadrenotools** (`rex_android_gpu_driver`) для загрузки кастомных драйверов **Mesa Turnip** на GPU Qualcomm Adreno без root.
  - Сенсорное управление (`TouchControlsView`) и поддержка физических геймпадов.
  - Автоматическая привязка потоков игры к быстрым ядрам CPU (`android_rendimiento.cpp`).

---

## Структура репозитория

```text
├── app/                        # CMake-проект игры F1 2012 (f1_2012)
│   ├── f1_2012_manifest.toml   # Манифест ReXGlue (вход: ../assets/game_root/default.xex)
│   ├── f1_2012.toml            # Конфигурация рантайма (gpu_plugin = "xenos", vsync)
│   ├── generated/rexglue.cmake # Подключение SDK и сгенерированного кода
│   └── src/                    # Точка входа (main.cpp, f1_2012_app.h, stub для CI)
├── sdk/                        # ReXGlue SDK с патчами под Android ARM64 / Vulkan / SDL3
├── android/                    # Gradle-проект Android APK (com.f12012.android)
│   └── app/src/main/cpp/       # JNI-мост, libadrenotools-диагностика, тач-ввод, CPU affinity
└── .github/workflows/          # Облачная сборка APK и codegen в GitHub Actions
```

---

## Сборка (GitHub Actions)

Тяжёлая компиляция выполняется в облаке через `.github/workflows/android.yml` (`app-release.apk`, `-O3 + ThinLTO`):

1. **Обычный `push` (с кэшем codegen + `ccache`):**
   - Использует закэшированный сгенерированный C++-код (`app/src/generated/default/`) и `ccache` для быстрой пересборки `libmain.so` и Android-оболочки (или подтягивает готовый `libmain.so` из `recomp-precompiled` при UI-правках).
2. **Полная перегенерация (`workflow_dispatch` → `xex_url` или `recomp-from-gdrive.yml`):**
   - Собирает хост-утилиту `rexglue`, прогоняет `rexglue codegen --force app/f1_2012_manifest.toml` по `default.xex` (`434D0847`) и компилирует финальный ARM64 Release APK.

---

## Требования к устройству и файлам игры

- **ОС:** Android 8.0+ (API 26+), архитектура `arm64-v8a`.
- **GPU:** Vulkan 1.1+ (для Adreno рекомендуется загрузка актуального драйвера Mesa Turnip через встроенный менеджер драйверов в лаунчере).
- **Файлы игры:** распакованная копия **F1 2012 (Xbox 360, Title ID: 434D0847)** с `default.xex` и папками ресурсов. Игровые файлы в репозиторий и APK не включаются.

---

## Лицензия

Код Android-обвязки распространяется по лицензии **GPL-3.0** (см. `LICENSE`). Компоненты ReXGlue SDK и сторонние библиотеки сохраняют свои исходные лицензии (`THIRD_PARTY_NOTICES.md`).

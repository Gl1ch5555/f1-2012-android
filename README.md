# F1 2012 Android (ARM64)

Нативный порт игры **F1 2012** (Xbox 360, Title ID: `434D0847`, движок **EGO 2.0**) для **Android (`arm64-v8a`)** на базе статической рекомпиляции PowerPC → C++ с помощью **ReXGlue SDK**.

> Проект находится на ранней стадии разработки. Архитектура Android-обвязки и Vulkan/Adrenotools-интеграции основана на [nfsmw-android](https://github.com/codepdbh/nfsmw-android) и [rexglue-sdk](https://github.com/rexglue/rexglue-sdk).

---

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

Тяжёлая компиляция выполняется в облаке через `.github/workflows/android.yml`:

1. **Без `default.xex` (текущий smoke-режим):**
   - Собирается вся Android-оболочка, `SDL3`, `rexruntime`, `rexgpu-xenos`, `libadrenotools`-хуки и заглушка `PPCImageConfig` (`f1_2012_stub.cpp`).
   - На выходе формируется артефакт `f1-2012-android-arm64-debug` (`app-debug.apk`).
2. **С `default.xex` (полная рекомпиляция игры):**
   - Поместите `default.xex` от Xbox 360-версии F1 2012 (`434D0847`) в `assets/game_root/default.xex` (или передайте прямую ссылку в `workflow_dispatch` → `xex_url`).
   - Workflow соберёт хост-утилиту `rexglue`, выполнит `rexglue codegen app/f1_2012_manifest.toml` и скомпилирует переведённый C++-код игры в `libmain.so` внутри ARM64 APK.

---

## Требования к устройству и файлам игры

- **ОС:** Android 8.0+ (API 26+), архитектура `arm64-v8a`.
- **GPU:** Vulkan 1.1+ (для Adreno рекомендуется загрузка актуального драйвера Mesa Turnip через встроенный менеджер драйверов в лаунчере).
- **Файлы игры:** распакованная копия **F1 2012 (Xbox 360, Title ID: 434D0847)** с `default.xex` и папками ресурсов. Игровые файлы в репозиторий и APK не включаются.

---

## Лицензия

Код Android-обвязки распространяется по лицензии **GPL-3.0** (см. `LICENSE`). Компоненты ReXGlue SDK и сторонние библиотеки сохраняют свои исходные лицензии (`THIRD_PARTY_NOTICES.md`).

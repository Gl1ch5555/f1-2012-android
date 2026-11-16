// f1_2012 - ReXGlue Recompiled Project (F1 2012, Xbox 360, Title ID 434D0847)

#pragma once

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/rex_app.h>

#include <filesystem>
#include <string>

#if REX_PLATFORM_ANDROID
extern "C" void f1_android_nucleos_grandes_aplicar();
#endif

class F12012App : public rex::ReXApp {
 public:
  using rex::ReXApp::ReXApp;

  static std::unique_ptr<rex::ui::WindowedApp> Create(
      rex::ui::WindowedAppContext& ctx) {
    return std::unique_ptr<F12012App>(
        new F12012App(ctx, "f1_2012", PPCImageConfig));
  }

 protected:
  void OnPreSetup(rex::RuntimeConfig& config) override {
    (void)config;
#if REX_PLATFORM_ANDROID
    f1_android_nucleos_grandes_aplicar();
#endif
  }

  void OnConfigurePaths(rex::PathConfig& paths) override {
    if (paths.game_data_root.empty()) {
      const auto exe_dir = rex::filesystem::GetExecutableFolder();
      const auto local_root = exe_dir / "game_root";
      if (std::filesystem::exists(local_root)) {
        paths.game_data_root = local_root;
      }
    }
    rex::cvar::SetFlagByName("gpu_plugin", "xenos");
  }
};

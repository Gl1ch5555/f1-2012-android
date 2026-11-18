package com.f12012.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Launcher graphics & runtime options mapped to real ReXGlue SDK cvars.
 */
final class GameOptions {
    static final class Option {
        final String key;
        final String title;
        final String cvar;
        final String[] values;
        final String[] labels;
        final String defaultValue;

        Option(String key, String title, String cvar, String defaultValue, String[] values, String[] labels) {
            this.key = key;
            this.title = title;
            this.cvar = cvar;
            this.defaultValue = defaultValue;
            this.values = values;
            this.labels = labels;
        }

        String label(String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return labels[i];
                }
            }
            return value;
        }
    }

    static final String RESOLUTION = "rt_path";
    static final String FPS = "vsync";
    static final String RENDERER = "renderer";
    static final String API = "graphics_api";

    static final Option[] ALL = {
            new Option(RENDERER, "GPU Backend", "gpu_plugin", "xenos",
                    new String[] {"xenos"},
                    new String[] {"Xenos Vulkan (ReXGlue)"}),
            new Option(RESOLUTION, "Render Target Path", "render_target_path_vulkan", "",
                    new String[] {"", "fbo", "fsi"},
                    new String[] {"Авто", "FBO (Host Render Targets)", "FSI (Fragment Shader Interlock)"}),
            new Option(FPS, "VSync", "vsync", "true",
                    new String[] {"true", "false"},
                    new String[] {"Вкл (60 Гц)", "Выкл (без лимита)"}),
            new Option("async_skip", "Пропуск неполных кадров (Async)", "vulkan_async_skip_incomplete_frames", "true",
                    new String[] {"true", "false"},
                    new String[] {"Вкл (рекомендуется)", "Выкл"}),
            new Option("invalid_fetch", "Игнорировать битые fetch-константы", "gpu_allow_invalid_fetch_constants", "true",
                    new String[] {"true", "false"},
                    new String[] {"Вкл (фикс чёрного 3D)", "Выкл"}),
            new Option("readback_resolve", "Readback Resolve (HDR / тени)", "readback_resolve", "some",
                    new String[] {"some", "fast", "none", "full"},
                    new String[] {"Some (автоэкспозиция, рекомендуемо)", "Fast (каждый кадр)", "Выкл", "Full (синхронный, медленно)"}),
            new Option("gpu_cap_msaa", "Срезать MSAA основной сцены", "gpu_cap_msaa", "true",
                    new String[] {"true", "false"},
                    new String[] {"Вкл (быстрее, EDRAM-тени целы)", "Выкл (родной MSAA)"}),
            new Option("native_msaa", "Нативный 2x MSAA", "native_2x_msaa", "false",
                    new String[] {"false", "true"},
                    new String[] {"Выкл (рекомендуется)", "Вкл"}),
            new Option("gamma_unorm16", "Gamma RT как UNORM16", "gamma_render_target_as_unorm16", "false",
                    new String[] {"false", "true"},
                    new String[] {"Выкл (sRGB, фикс блума)", "Вкл (UNORM16)"}),
            new Option("dyn_rendering", "Vulkan Dynamic Rendering", "vulkan_dynamic_rendering", "false",
                    new String[] {"false", "true"},
                    new String[] {"Выкл (VkRenderPass, стабильнее)", "Вкл (по умолчанию SDK)"}),
            new Option("tex_3d_to_2d", "3D-текстуры как 2D", "gpu_3d_to_2d_texture", "true",
                    new String[] {"true", "false"},
                    new String[] {"Вкл (по умолчанию)", "Выкл"}),
            new Option("non_seamless_cube", "Non-seamless Cubemap", "non_seamless_cube_map", "false",
                    new String[] {"false", "true"},
                    new String[] {"Выкл", "Вкл"}),
            new Option("native_shader_feat", "Нативные фичи шейдеров Vulkan", "vulkan_native_shader_features", "false",
                    new String[] {"false", "true"},
                    new String[] {"Выкл (совместимость)", "Вкл"}),
    };

    private static final String PREFS = "f1_2012_game";
    private static final String DEFAULTS_VERSION_KEY = "defaults_version";
    private static final int DEFAULTS_VERSION = 2;

    private GameOptions() {
    }

    static SharedPreferences prefs(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getInt(DEFAULTS_VERSION_KEY, 0) < DEFAULTS_VERSION) {
            p.edit()
                    .putString("gamma_unorm16", "false")
                    .putString("readback_resolve", "some")
                    .putString("dyn_rendering", "false")
                    .putString("gpu_cap_msaa", "true")
                    .putInt(DEFAULTS_VERSION_KEY, DEFAULTS_VERSION)
                    .apply();
        }
        return p;
    }

    static Option find(String key) {
        for (Option o : ALL) {
            if (o.key.equals(key)) {
                return o;
            }
        }
        throw new IllegalArgumentException(key);
    }

    static String get(Context context, String key) {
        Option o = find(key);
        String value = prefs(context).getString(key, o.defaultValue);
        for (String allowed : o.values) {
            if (allowed.equals(value)) {
                return value;
            }
        }
        return o.defaultValue;
    }

    static void set(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    static boolean usesGles(Context context) {
        return false;
    }

    static List<String> arguments(Context context) {
        List<String> args = new ArrayList<>();
        for (Option o : ALL) {
            String val = get(context, o.key);
            if (!val.isEmpty()) {
                args.add("--" + o.cvar + "=" + val);
            }
        }
        args.add("--vulkan_require_geometry_shader=false");
        args.add("--vulkan_require_fill_mode_non_solid=false");
        GpuDrivers.arguments(context, args);
        return args;
    }
}

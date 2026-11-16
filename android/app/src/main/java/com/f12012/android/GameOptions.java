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
                    new String[] {"Auto", "FBO (Host Render Targets)", "FSI (Fragment Shader Interlock)"}),
            new Option(FPS, "VSync", "vsync", "true",
                    new String[] {"true", "false"},
                    new String[] {"On (60 Hz)", "Off (Unlocked)"}),
            new Option("async_skip", "Async Skip Incomplete Frames", "vulkan_async_skip_incomplete_frames", "true",
                    new String[] {"true", "false"},
                    new String[] {"On (Recommended)", "Off"}),
    };

    private static final String PREFS = "f1_2012_game";

    private GameOptions() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        args.add("--vulkan_native_shader_features=false");
        args.add("--vulkan_require_geometry_shader=false");
        args.add("--vulkan_require_fill_mode_non_solid=false");
        GpuDrivers.arguments(context, args);
        return args;
    }
}

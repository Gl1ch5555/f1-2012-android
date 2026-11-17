// SPDX-License-Identifier: GPL-3.0-only
// Reversible presets, following the fork's "Mis ajustes" idea without copying its launcher.
package com.f12012.android;
import java.util.*;

final class GraphicsPresets {
    static final String[] KEYS = {"rt_path", "vsync", "async_skip"};
    static final String[] NAMES = {"Battery", "Balanced", "Performance", "Quality"};
    static Map<String,String> values(int preset) {
        String[][] data = {
            {"fbo", "true", "true"},
            {"", "true", "true"},
            {"fbo", "false", "true"},
            {"fsi", "true", "false"}
        };
        if (preset < 0 || preset >= data.length) throw new IllegalArgumentException("Неверный пресет");
        Map<String,String> result = new LinkedHashMap<>();
        for (int i = 0; i < KEYS.length; ++i) result.put(KEYS[i], data[preset][i]);
        return result;
    }
    static Map<String,String> preserve(Map<String,String> current, Map<String,String> applied, Map<String,String> saved) {
        return new LinkedHashMap<>(current.equals(applied) && saved != null ? saved : current);
    }
}

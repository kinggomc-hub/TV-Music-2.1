package com.lyricplayer.tvn;

import android.content.Context;
import android.content.SharedPreferences;

/** 설정 저장. 효과 항목은 기본 켜짐. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("native", Context.MODE_PRIVATE);
    }

    boolean on(String key) {
        return sp.getBoolean(key, true);
    }

    boolean get(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    void set(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    /** 그리는 해상도: 720 또는 1080 */
    int res() {
        return sp.getInt("res", 720) == 1080 ? 1080 : 720;
    }

    void setRes(int r) {
        sp.edit().putInt("res", r == 1080 ? 1080 : 720).apply();
    }
}

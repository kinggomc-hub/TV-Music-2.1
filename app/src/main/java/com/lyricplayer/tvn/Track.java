package com.lyricplayer.tvn;

import java.io.File;

final class Track {
    final File audio;
    final File lrc; // 없으면 null
    final String title;
    final String dir;

    Track(File audio, File lrc, String title, String dir) {
        this.audio = audio;
        this.lrc = lrc;
        this.title = title;
        this.dir = dir;
    }
}

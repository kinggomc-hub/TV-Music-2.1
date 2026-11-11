package com.lyricplayer.tvn;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 마운트된 USB/SD 저장장치에서 음악과 같은 이름의 가사(.lrc/.txt)를 찾는다. 파일 관리자 앱은 쓰지 않는다. */
final class FileScanner {
    interface Progress {
        void on(int found);
    }

    static final class Result {
        final List<Track> tracks;
        final String note;

        Result(List<Track> tracks, String note) {
            this.tracks = tracks;
            this.note = note;
        }
    }

    private static final int MAX_FILES = 8000, MAX_DEPTH = 12;

    static Result scan(Context ctx, Progress p) {
        List<File> roots = removableRoots(ctx);
        List<File> audios = new ArrayList<>();
        Map<String, File> lrcs = new HashMap<>();
        int readable = 0, unreadable = 0;
        for (File r : roots) {
            File[] ch = r.listFiles();
            if (ch == null) {
                unreadable++;
                continue;
            }
            readable++;
            walk(r, 0, audios, lrcs, p);
        }
        List<Track> out = new ArrayList<>();
        for (File a : audios) {
            String base = baseName(a.getName());
            File l = lrcs.get(key(a.getParentFile(), base));
            out.add(new Track(a, l, base, a.getParentFile() == null ? "" : a.getParentFile().getAbsolutePath()));
        }
        Collections.sort(out, new Comparator<Track>() {
            @Override
            public int compare(Track x, Track y) {
                return (x.dir + "/" + x.audio.getName()).compareToIgnoreCase(y.dir + "/" + y.audio.getName());
            }
        });
        String note = "";
        if (out.isEmpty()) {
            if (readable > 0) note = "USB는 읽었지만 음악 파일(mp3, flac, m4a, aac, wav, ogg, opus)을 찾지 못했습니다.";
            else if (unreadable > 0) note = "USB는 연결되어 있지만 읽기 권한이 없습니다. 저장소 권한을 허용했는지 확인하세요.";
            else note = "마운트된 USB/SD 저장장치를 찾지 못했습니다. 장치를 연결한 뒤 다시 시도하세요.";
        }
        return new Result(out, note);
    }

    private static List<File> removableRoots(Context ctx) {
        List<File> roots = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    File dir = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        dir = v.getDirectory();
                    } else {
                        try {
                            Object path = StorageVolume.class.getMethod("getPath").invoke(v);
                            if (path != null) dir = new File((String) path);
                        } catch (Exception ignored) {
                        }
                    }
                    if (dir != null && !roots.contains(dir)) roots.add(dir);
                }
            }
        } catch (Throwable ignored) {
        }
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || n.startsWith(".")) continue;
                if (f.isDirectory() && !roots.contains(f)) roots.add(f);
            }
        }
        if (roots.isEmpty()) { // 오래된 셋톱박스의 USB 경로
            String[] bases = {"/mnt/usb_storage", "/mnt/usbhost", "/mnt/media_rw", "/mnt/sda"};
            for (String b : bases) {
                File[] ch = new File(b).listFiles();
                if (ch != null) for (File f : ch) if (f.isDirectory()) roots.add(f);
            }
        }
        return roots;
    }

    private static void walk(File dir, int depth, List<File> audios, Map<String, File> lrcs, Progress p) {
        if (depth > MAX_DEPTH || audios.size() >= MAX_FILES) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (skip(n)) continue;
            if (f.isDirectory()) {
                walk(f, depth + 1, audios, lrcs, p);
            } else {
                int k = kind(n);
                if (k == 1) {
                    audios.add(f);
                    if (p != null && audios.size() % 50 == 0) p.on(audios.size());
                } else if (k == 2) {
                    String key = key(dir, baseName(n));
                    File old = lrcs.get(key);
                    boolean isLrc = n.toLowerCase(Locale.ROOT).endsWith(".lrc");
                    if (old == null || isLrc) lrcs.put(key, f); // .lrc 를 .txt 보다 우선
                }
            }
        }
    }

    private static boolean skip(String n) {
        if (n.startsWith(".") || n.startsWith("$")) return true;
        String l = n.toLowerCase(Locale.ROOT);
        return l.equals("android") || l.equals("lost.dir") || l.equals("system volume information")
                || l.equals("found.000");
    }

    /** 1 = 음악, 2 = 가사, 0 = 무관 */
    private static int kind(String n) {
        int d = n.lastIndexOf('.');
        if (d < 0) return 0;
        switch (n.substring(d + 1).toLowerCase(Locale.ROOT)) {
            case "mp3": case "flac": case "m4a": case "aac": case "wav": case "ogg": case "opus":
                return 1;
            case "lrc": case "txt":
                return 2;
            default:
                return 0;
        }
    }

    private static String baseName(String n) {
        return n.replaceFirst("\\.[^.]+$", "");
    }

    private static String key(File dir, String base) {
        String d = dir == null ? "" : dir.getAbsolutePath();
        return Normalizer.normalize(d + "|" + base, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}

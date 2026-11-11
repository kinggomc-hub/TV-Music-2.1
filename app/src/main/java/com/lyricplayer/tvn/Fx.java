package com.lyricplayer.tvn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** 효과 목록과 곡마다의 랜덤 선택 규칙. */
final class Fx {
    static final class Def {
        final String key, name;

        Def(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /** 배경: 곡마다 1개. 번호는 GlRenderer 의 셰이더와 맞춰져 있음. */
    static final Def[] BG = {
            new Def("bg_stars", "별 반짝임"),               // 0
            new Def("bg_breath", "숨 쉬는 빛"),             // 1
            new Def("bg_blobs", "떠다니는 빛 덩어리"),       // 2
            new Def("bg_flow", "흐르는 그라데이션"),         // 3
            new Def("bg_rays", "지나가는 빛줄기"),           // 4
            new Def("bg_meteor", "가끔 지나가는 유성"),      // 5
            new Def("bg_dust", "느리게 오르는 먼지 점"),     // 6
            new Def("bg_waves", "은은한 가로 파동선"),       // 7
            new Def("bg_vig", "테두리 어둠이 숨 쉼"),        // 8
            new Def("bg_aurora", "오로라 커튼"),            // 9
            new Def("bg_sunset", "노을빛 띠"),              // 10
            new Def("bg_deep", "깊이감 빛 덩어리"),          // 11
            new Def("bg_mist", "안개 흐름"),                // 12
            new Def("bg_sweep", "스포트라이트 스윕"),        // 13
            new Def("bg_ribbon", "대각선 리본"),            // 14
            new Def("bg_orbit", "공전하는 빛")              // 15
    };

    /** 줄이 나타나는 효과 (0~13: 줄 단위, 14~18: 단어/글자 단위). */
    static final Def[] ENTER = {
            new Def("en_rise", "아래에서 살짝 떠오름"),         // 0
            new Def("en_drop", "위에서 살짝 내려옴"),           // 1
            new Def("en_grow", "살짝 커지며 나타남"),           // 2
            new Def("en_shrink", "줄어들며 나타남"),            // 3
            new Def("en_slide", "옆에서 짧게 밀려옴"),          // 4
            new Def("en_revlr", "왼쪽에서 오른쪽으로 드러남"),   // 5
            new Def("en_revc", "가운데서 양옆으로 열림"),        // 6
            new Def("en_revtd", "위에서 아래로 드러남"),         // 7
            new Def("en_tilt", "살짝 기울었다 바로잡힘"),        // 8
            new Def("en_diag", "대각선으로 살짝 들어옴"),        // 9
            new Def("en_stretchx", "가로로 펼쳐짐"),            // 10
            new Def("en_stretchy", "세로로 펼쳐짐"),            // 11
            new Def("en_revbu", "아래에서 위로 드러남"),         // 12
            new Def("en_revrl", "오른쪽에서 왼쪽으로 드러남"),   // 13
            new Def("en_words", "단어가 차례로 나타남"),         // 14
            new Def("en_letters", "글자가 차례로 밀려옴"),       // 15
            new Def("en_center", "가운데서 바깥으로 퍼짐"),      // 16
            new Def("en_wdown", "단어가 위에서 차례로 내려옴"),   // 17
            new Def("en_lup", "글자가 아래에서 차례로 떠오름")    // 18
    };
    static final int SEG_FIRST = 14;

    /** 단어/글자 효과가 조각을 나누는 방식: 1 = 단어, 2 = 글자, 3 = 글자(가운데부터) */
    static int segMode(int id) {
        switch (id) {
            case 14:
            case 17:
                return 1;
            case 15:
            case 18:
                return 2;
            case 16:
                return 3;
            default:
                return 0;
        }
    }

    /** 줄이 사라지는 효과 (곡마다 1개). */
    static final Def[] EXIT = {
            new Def("ex_fade", "서서히 투명해짐"),              // 0
            new Def("ex_up", "위로 살짝 올라가며 사라짐"),       // 1
            new Def("ex_shrink", "살짝 작아지며 사라짐"),        // 2
            new Def("ex_side", "옆으로 살짝 밀리며 사라짐"),     // 3
            new Def("ex_grow", "살짝 커지며 사라짐")            // 4
    };

    /** 강조 장식 (가끔, 한 줄에 단어 하나). */
    static final Def[] DECO = {
            new Def("dc_box", "단어 흰 박스 반전"),             // 0
            new Def("dc_under", "단어 밑줄"),                   // 1
            new Def("dc_big", "단어 살짝 크게")                 // 2
    };

    /** 최근 곡의 선택 기록 (연속 반복 방지). */
    static final class History {
        final ArrayList<Integer> bg = new ArrayList<>();
        String lastEnter = "";
    }

    /** 한 곡에 적용되는 효과 묶음. */
    static final class Song {
        int bg = -1;                 // -1 이면 기본 그라데이션만
        float hue, spd, seedX, seedY;
        int[] enter = new int[0];    // 이 곡에서 섞어 쓰는 나타남 효과 (2~3개)
        int exit = -1;               // -1 이면 이전 줄이 바로 사라짐
        int dir = 1;                 // 옆으로 밀려오는 방향 (곡마다 고정)
        final boolean[] decoOn = new boolean[DECO.length];
        private int lastEnter = -1;
        private int sinceDeco = 0;

        synchronized int nextEnter(Random r) {
            if (enter.length == 0) return -1;
            int pick = enter[r.nextInt(enter.length)];
            if (enter.length > 1) {
                int guard = 0;
                while (pick == lastEnter && guard++ < 8) pick = enter[r.nextInt(enter.length)];
            }
            lastEnter = pick;
            return pick;
        }

        /** 5~6줄에 한 번쯤: 최소 3줄 간격, 곡 시작 첫 2줄은 건너뜀. 없으면 -1. */
        synchronized int nextDeco(Random r, int lineIdx) {
            sinceDeco++;
            if (lineIdx < 2 || sinceDeco < 4) return -1;
            if (r.nextFloat() >= 0.35f) return -1;
            List<Integer> on = new ArrayList<>();
            for (int i = 0; i < decoOn.length; i++) if (decoOn[i]) on.add(i);
            if (on.isEmpty()) return -1;
            sinceDeco = 0;
            return on.get(r.nextInt(on.size()));
        }
    }

    static Song create(Prefs p, String title, History h, Random r) {
        Song s = new Song();
        int hv = title == null ? 0 : title.hashCode();
        Random tr = new Random(hv ^ 0x9E3779B9L);
        s.hue = (float) (((hv >>> 3) & 0x7fffffff) % 360) / 360f;      // 곡 제목에서 정한 색
        s.spd = 0.75f + (float) (((hv >>> 11) & 0x7fffffff) % 50) / 100f; // 0.75 ~ 1.24배 속도
        s.seedX = tr.nextFloat();
        s.seedY = tr.nextFloat();
        s.dir = r.nextBoolean() ? 1 : -1;

        // 배경: 켜진 것 중에서, 최근 3곡에서 쓴 것은 피해서 1개
        List<Integer> bgOn = new ArrayList<>();
        for (int i = 0; i < BG.length; i++) if (p.on(BG[i].key)) bgOn.add(i);
        if (!bgOn.isEmpty()) {
            List<Integer> cand = new ArrayList<>(bgOn);
            cand.removeAll(h.bg);
            if (cand.isEmpty()) cand = bgOn;
            s.bg = cand.get(r.nextInt(cand.size()));
            h.bg.add(s.bg);
            while (h.bg.size() > 3) h.bg.remove(0);
        }

        // 나타남 효과: 켜진 것 중 2~3개. 단어/글자 효과만 나오는 곡이 없도록 줄 단위 효과를 하나는 포함
        List<Integer> line = new ArrayList<>(), seg = new ArrayList<>();
        for (int i = 0; i < ENTER.length; i++) {
            if (!p.on(ENTER[i].key)) continue;
            if (i >= SEG_FIRST) seg.add(i); else line.add(i);
        }
        int total = line.size() + seg.size();
        if (total > 0) {
            int[] best = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                int k = Math.min(total, 2 + r.nextInt(2));
                List<Integer> pool = new ArrayList<>(line);
                pool.addAll(seg);
                List<Integer> chosen = new ArrayList<>();
                if (!line.isEmpty()) chosen.add(line.get(r.nextInt(line.size())));
                pool.removeAll(chosen);
                while (chosen.size() < k && !pool.isEmpty()) chosen.add(pool.remove(r.nextInt(pool.size())));
                int[] arr = new int[chosen.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = chosen.get(i);
                Arrays.sort(arr);
                best = arr;
                if (!Arrays.toString(arr).equals(h.lastEnter)) break; // 바로 앞 곡과 같은 조합은 피함
            }
            s.enter = best;
            h.lastEnter = Arrays.toString(best);
        }

        // 사라짐: 켜진 것 중 1개
        List<Integer> exOn = new ArrayList<>();
        for (int i = 0; i < EXIT.length; i++) if (p.on(EXIT[i].key)) exOn.add(i);
        if (!exOn.isEmpty()) s.exit = exOn.get(r.nextInt(exOn.size()));

        for (int i = 0; i < DECO.length; i++) s.decoOn[i] = p.on(DECO[i].key);
        return s;
    }
}

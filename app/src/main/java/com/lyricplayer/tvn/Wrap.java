package com.lyricplayer.tvn;

import java.util.ArrayList;
import java.util.List;

/**
 * 긴 가사 줄바꿈: 안드로이드 기본 줄바꿈은 한글 단어 중간에서도 끊기 때문에, 띄어쓰기에서만 줄을 나눈다.
 * 줄 수는 가장 적게(탐욕법) 잡고, 그 줄 수 안에서 가장 좁은 폭으로 균형 있게 나눈다(마지막 줄만 짧아지지 않게).
 */
final class Wrap {
    interface Measure {
        float width(String s);
    }

    static String balanced(String text, Measure m, float maxW) {
        String t = text.trim();
        if (t.isEmpty()) return t;
        String[] words = t.split("\\s+");
        if (words.length <= 1) return t;
        String one = join(words);
        if (m.width(one) <= maxW) return one;

        float longest = 0f;
        for (String w : words) longest = Math.max(longest, m.width(w));
        List<String> base = greedy(words, m, maxW);
        if (longest >= maxW || base.size() <= 1) return joinLines(base); // 한 단어가 폭보다 길면 그 단어는 따로 한 줄

        int n = base.size();
        float lo = longest, hi = maxW;
        for (int i = 0; i < 14; i++) {
            float mid = (lo + hi) * 0.5f;
            if (greedy(words, m, mid).size() <= n) hi = mid;
            else lo = mid;
        }
        return joinLines(greedy(words, m, hi));
    }

    private static List<String> greedy(String[] words, Measure m, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            if (cur.length() == 0) {
                cur.append(w);
            } else if (m.width(cur + " " + w) <= width) {
                cur.append(' ').append(w);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(w);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private static String join(String[] words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}

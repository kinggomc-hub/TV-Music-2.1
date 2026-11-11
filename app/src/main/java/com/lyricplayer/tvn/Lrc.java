package com.lyricplayer.tvn;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 가사 읽기 (줄 단위). */
final class Lrc {
    static final class Line {
        final long ms;
        final String text;

        Line(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    static List<Line> parse(String src) {
        List<Line> out = new ArrayList<>();
        long offset = 0;
        for (String raw : src.split("\r?\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            Matcher mo = OFFSET.matcher(s);
            if (mo.matches()) {
                try {
                    offset = Long.parseLong(mo.group(1));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            List<Long> times = new ArrayList<>();
            int pos = 0;
            while (true) {
                Matcher m = TS.matcher(s);
                m.region(pos, s.length());
                if (!m.lookingAt()) break;
                long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    long f = Long.parseLong(frac);
                    ms += frac.length() == 1 ? f * 100 : frac.length() == 2 ? f * 10 : f;
                }
                times.add(ms);
                pos = m.end();
            }
            if (times.isEmpty()) continue; // [ti:..] 같은 정보 줄과 시간 없는 줄은 건너뜀
            String text = WORD_TAG.matcher(s.substring(pos)).replaceAll("").trim();
            for (long t : times) out.add(new Line(Math.max(0, t + offset), text));
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return a.ms < b.ms ? -1 : (a.ms > b.ms ? 1 : 0);
            }
        });
        return out;
    }

    /** UTF-8(BOM 포함)을 먼저 시도하고, 실패하면 EUC-KR(CP949)로 읽는다. */
    static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(b, Charset.forName("EUC-KR"));
            } catch (Exception x) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
    }
}

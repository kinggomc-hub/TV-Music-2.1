package com.lyricplayer.tvn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 가사 한 줄을 비트맵으로 만든 것(텍스처가 된다). 글자 그리기는 CPU 에서 줄이 바뀔 때만 한 번 한다. */
final class LineTex {
    int w, h;            // 비트맵(텍스처) 크기
    Bitmap bmp;          // GL 로 올린 뒤 해제
    int tex;             // GL 텍스처 이름
    float fontPx;
    int enter = -1;      // 이 줄의 나타남 효과 번호
    float[][] segs = new float[0][];   // 단어/글자 조각 {x0,y0,x1,y1} (비트맵 픽셀)
    int[] rank = new int[0];           // 조각이 나타나는 순서
    String text;

    private static final Pattern WORD = Pattern.compile("\\S{2,}");

    static LineTex build(String text, int vw, int vh, int enter, int deco, Random rnd) {
        LineTex lt = new LineTex();
        lt.text = text;
        lt.enter = enter;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        int maxW = (int) (vw * 0.84f), maxH = (int) (vh * 0.66f);
        float size = vh * 0.13f, minSize = vh * 0.045f;
        final TextPaint mp = tp;
        Wrap.Measure measure = new Wrap.Measure() {
            @Override
            public float width(String s) {
                return mp.measureText(s);
            }
        };
        // 띄어쓰기에서만 줄을 나누고(단어가 중간에서 잘리지 않게), 화면에 들어올 때까지 글자 크기를 줄인다
        String wt;
        StaticLayout lay;
        while (true) {
            tp.setTextSize(size);
            wt = Wrap.balanced(text, measure, maxW);
            lay = new StaticLayout(wt, tp, maxW, Layout.Alignment.ALIGN_CENTER, 1.12f, 0f, false);
            float widest = 0f;
            for (int i = 0; i < lay.getLineCount(); i++) widest = Math.max(widest, lay.getLineWidth(i));
            if ((lay.getHeight() <= maxH && widest <= maxW + 1f) || size <= minSize) break;
            size *= 0.92f;
        }
        lt.fontPx = size;

        // 강조할 단어 고르기: 2글자 이상이고 한 줄 안에 들어 있는 단어
        int ws = -1, we = -1;
        if (deco >= 0) {
            List<int[]> cand = new ArrayList<>();
            Matcher m = WORD.matcher(wt);
            while (m.find()) {
                if (lay.getLineForOffset(m.start()) == lay.getLineForOffset(m.end() - 1)) {
                    cand.add(new int[]{m.start(), m.end()});
                }
            }
            if (!cand.isEmpty()) {
                int[] c = cand.get(rnd.nextInt(cand.size()));
                ws = c[0];
                we = c[1];
            }
        }
        if (ws >= 0 && (deco == 0 || deco == 2)) {
            SpannableStringBuilder sb = new SpannableStringBuilder(wt);
            if (deco == 0) {
                sb.setSpan(new BackgroundColorSpan(Color.WHITE), ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Color.BLACK), ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.setSpan(new RelativeSizeSpan(1.08f), ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            lay = new StaticLayout(sb, tp, maxW, Layout.Alignment.ALIGN_CENTER, 1.12f, 0f, false);
        }

        float minL = Float.MAX_VALUE, maxR = 0f;
        for (int i = 0; i < lay.getLineCount(); i++) {
            minL = Math.min(minL, lay.getLineLeft(i));
            maxR = Math.max(maxR, lay.getLineRight(i));
        }
        if (minL > maxR) {
            minL = 0f;
            maxR = 1f;
        }
        int pad = (int) (size * 0.14f) + 4;
        lt.w = (int) Math.ceil(maxR - minL) + 2 * pad;
        lt.h = lay.getHeight() + 2 * pad;
        Bitmap bmp = Bitmap.createBitmap(lt.w, lt.h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.translate(pad - minL, pad);
        lay.draw(c);

        // 직접 그리는 장식: 밑줄
        if (ws >= 0 && deco == 1) {
            int line = lay.getLineForOffset(ws);
            float x0 = lay.getPrimaryHorizontal(ws), x1 = lay.getPrimaryHorizontal(we);
            if (x1 < x0) {
                float t = x0;
                x0 = x1;
                x1 = t;
            }
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setColor(Color.WHITE);
            float sw = Math.max(2f, size * 0.05f);
            float y = lay.getLineBaseline(line) + size * 0.10f;
            c.drawRect(x0, y, x1, y + sw, pt);
        }
        lt.bmp = bmp;

        // 단어/글자 효과용 조각
        if (enter >= Fx.SEG_FIRST) buildSegs(lt, lay, wt, Fx.segMode(enter), pad, minL);
        return lt;
    }

    private static boolean ws(char ch) {
        return Character.isWhitespace(ch);
    }

    private static void buildSegs(LineTex lt, StaticLayout lay, String text, int mode, int pad, float minL) {
        ArrayList<float[]> list = new ArrayList<>();
        int n = text.length();
        if (mode == 1) {
            int i = 0;
            while (i < n) {
                while (i < n && ws(text.charAt(i))) i++;
                int s = i;
                while (i < n && !ws(text.charAt(i))) i++;
                if (i > s) addRange(list, lay, s, i, pad, minL, lt.w, lt.h);
            }
        } else {
            int cnt = 0;
            for (int i = 0; i < n; i++) if (!ws(text.charAt(i))) cnt++;
            int g = Math.max(1, (cnt + 29) / 30); // 글자가 많으면 묶어서 조각 수를 30개 이하로
            int inGroup = 0, gs = -1;
            for (int i = 0; i < n; i++) {
                if (ws(text.charAt(i))) {
                    if (gs >= 0) {
                        addRange(list, lay, gs, i, pad, minL, lt.w, lt.h);
                        gs = -1;
                        inGroup = 0;
                    }
                    continue;
                }
                if (gs < 0) {
                    gs = i;
                    inGroup = 0;
                }
                inGroup++;
                if (inGroup == g) {
                    addRange(list, lay, gs, i + 1, pad, minL, lt.w, lt.h);
                    gs = -1;
                    inGroup = 0;
                }
            }
            if (gs >= 0) addRange(list, lay, gs, n, pad, minL, lt.w, lt.h);
        }
        lt.segs = list.toArray(new float[0][]);
        int m = lt.segs.length;
        lt.rank = new int[m];
        if (mode == 3) {
            // 가운데에서 가까운 조각부터
            Integer[] idx = new Integer[m];
            for (int i = 0; i < m; i++) idx[i] = i;
            final float cx = lt.w / 2f;
            final float[][] sg = lt.segs;
            Arrays.sort(idx, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    float da = Math.abs((sg[a][0] + sg[a][2]) / 2f - cx), db = Math.abs((sg[b][0] + sg[b][2]) / 2f - cx);
                    return da < db ? -1 : (da > db ? 1 : 0);
                }
            });
            for (int r = 0; r < m; r++) lt.rank[idx[r]] = r;
        } else {
            for (int i = 0; i < m; i++) lt.rank[i] = i;
        }
    }

    /** 글자 범위 [s,e) 를 줄마다 잘라서 사각형으로 추가 */
    private static void addRange(List<float[]> out, StaticLayout lay, int s, int e, int pad, float minL, int bw, int bh) {
        int l0 = lay.getLineForOffset(s), l1 = lay.getLineForOffset(Math.max(s, e - 1));
        for (int L = l0; L <= l1; L++) {
            int a = Math.max(s, lay.getLineStart(L)), b = Math.min(e, lay.getLineEnd(L));
            CharSequence cs = lay.getText();
            while (b > a && Character.isWhitespace(cs.charAt(b - 1))) b--;
            if (b <= a) continue;
            float x0 = lay.getPrimaryHorizontal(a) + pad - minL, x1 = lay.getPrimaryHorizontal(b) + pad - minL;
            if (x1 < x0) {
                float t = x0;
                x0 = x1;
                x1 = t;
            }
            float y0 = lay.getLineTop(L) + pad, y1 = lay.getLineBottom(L) + pad;
            x0 = Math.max(0f, x0);
            y0 = Math.max(0f, y0);
            x1 = Math.min((float) bw, x1);
            y1 = Math.min((float) bh, y1);
            if (x1 - x0 < 1f || y1 - y0 < 1f) continue;
            out.add(new float[]{x0, y0, x1, y1});
        }
    }
}

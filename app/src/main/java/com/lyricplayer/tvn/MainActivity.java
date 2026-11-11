package com.lyricplayer.tvn;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 네이티브 가사 플레이어 (시험판).
 * 소리: ExoPlayer 가 따로 재생(화면이 느려져도 영향 없음) / 화면: GLSurfaceView 고정 해상도(720p 또는 1080p, 30fps 제한).
 * 메뉴는 일반 안드로이드 View 이고 리모컨 방향키로 이동한다.
 */
public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 11;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Prefs prefs;
    private GLSurfaceView glView;
    private GlRenderer renderer;
    private ExoPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Fx.History hist = new Fx.History();

    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private int[] order = new int[0];
    private int pos = -1;
    private boolean started = false, scanning = false, firstScan = true;
    private int loadGen = 0, errCount = 0;
    private long backT = 0, lastInfo = 0;
    private float density = 1f;

    private FrameLayout root;
    private TextView fpsView, nowView, toastView, titleView, timeView, statusView;
    private String curLabel = "";
    private LinearLayout startPanel, ctrlPanel, settingsBox;
    private ScrollView settingsPanel;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private Button playBtn, usbBtn, resBtn;

    // ------------------------------------------------------------ 생성
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        density = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        buildUi();
        hideSystemUi();

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) ui.post(new Runnable() {
                    @Override
                    public void run() {
                        step(1);
                    }
                });
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (playing) errCount = 0;
                updatePlayLabel();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // 재생할 수 없는 곡은 건너뛴다 (연속 5회까지)
                if (++errCount <= 5) ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        step(1);
                    }
                }, 800);
                else toast("재생할 수 없는 파일이 계속됩니다");
            }
        });
        scan();
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    // ------------------------------------------------------------ 화면 구성
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0); // 565 로 두면 어두운 그라데이션에 줄무늬가 생긴다
        renderer = new GlRenderer(glView);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY); // 33ms 마다 직접 그려 달라고 요청(30fps)
        glView.setPreserveEGLContextOnPause(true);
        applyResolution();
        root.addView(glView, new FrameLayout.LayoutParams(MATCH, MATCH));

        fpsView = text("", 16, Color.WHITE);
        fpsView.setBackgroundColor(0x88000000);
        fpsView.setPadding(dp(8), dp(2), dp(8), dp(2));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        fl.setMargins(dp(12), dp(10), 0, 0);
        root.addView(fpsView, fl);

        nowView = text("", 20, Color.WHITE);
        nowView.setSingleLine(true);
        nowView.setEllipsize(TextUtils.TruncateAt.END);
        nowView.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        nowView.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nl.setMargins(dp(40), 0, dp(40), dp(18));
        root.addView(nowView, nl);

        buildStartPanel();
        buildCtrlPanel();
        buildSettingsPanel();

        toastView = text("", 22, Color.WHITE);
        toastView.setBackgroundColor(0xCC000000);
        toastView.setPadding(dp(20), dp(10), dp(20), dp(10));
        toastView.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        tl.setMargins(0, 0, 0, dp(120));
        root.addView(toastView, tl);

        setContentView(root);
    }

    private void buildStartPanel() {
        startPanel = new LinearLayout(this);
        startPanel.setOrientation(LinearLayout.VERTICAL);
        startPanel.setBackgroundColor(0xE6000000);
        startPanel.setPadding(dp(40), dp(28), dp(40), dp(24));

        TextView h = text("가사 플레이어 · 네이티브 시험판", 30, Color.WHITE);
        startPanel.addView(h, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        usbBtn = button("USB에서 음악 불러오기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        Button rb = button("랜덤 재생", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracks.isEmpty()) toast("먼저 USB에서 음악을 불러오세요");
                else startPlayback(-1);
            }
        });
        row.addView(usbBtn, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(WRAP, WRAP);
        rl.setMargins(dp(16), 0, 0, 0);
        row.addView(rb, rl);
        LinearLayout.LayoutParams rowL = new LinearLayout.LayoutParams(MATCH, WRAP);
        rowL.setMargins(0, dp(16), 0, dp(8));
        startPanel.addView(row, rowL);

        statusView = text("", 20, 0xFFBBBBBB);
        startPanel.addView(statusView, new LinearLayout.LayoutParams(MATCH, WRAP));

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int p, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, v, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                return t;
            }
        };
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setSelector(new ColorDrawable(0x66FFFFFF));
        listView.setDivider(null);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startPlayback(position);
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        ll.setMargins(0, dp(8), 0, 0);
        startPanel.addView(listView, ll);

        root.addView(startPanel, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void buildCtrlPanel() {
        ctrlPanel = new LinearLayout(this);
        ctrlPanel.setOrientation(LinearLayout.VERTICAL);
        ctrlPanel.setBackgroundColor(0xCC000000);
        ctrlPanel.setPadding(dp(32), dp(16), dp(32), dp(20));
        ctrlPanel.setVisibility(View.GONE);

        titleView = text("", 24, Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        ctrlPanel.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));
        timeView = text("", 18, 0xFFBBBBBB);
        ctrlPanel.addView(timeView, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("◀ 이전", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(-1);
            }
        }), btnLp());
        playBtn = button("정지", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        row.addView(playBtn, btnLp());
        row.addView(button("다음 ▶", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(1);
            }
        }), btnLp());
        row.addView(button("목록", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ctrlPanel.setVisibility(View.GONE);
                startPanel.setVisibility(View.VISIBLE);
                refreshNow();
                listView.requestFocus();
            }
        }), btnLp());
        row.addView(button("설정", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSettings();
            }
        }), btnLp());
        row.addView(button("종료", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndRemoveTask();
            }
        }), btnLp());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.setMargins(0, dp(10), 0, 0);
        ctrlPanel.addView(row, rl);

        root.addView(ctrlPanel, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.setMargins(0, 0, dp(12), 0);
        return p;
    }

    private void buildSettingsPanel() {
        settingsPanel = new ScrollView(this);
        settingsPanel.setBackgroundColor(0xF2000000);
        settingsPanel.setVisibility(View.GONE);
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setPadding(dp(28), dp(20), dp(28), dp(28));
        settingsPanel.addView(settingsBox, new FrameLayout.LayoutParams(MATCH, WRAP));

        settingsBox.addView(text("설정 (메뉴 버튼 / 뒤로: 닫기)", 26, Color.WHITE));

        resBtn = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setRes(prefs.res() == 720 ? 1080 : 720);
                applyResolution();
                updateResLabel();
                toast("그리는 해상도: " + prefs.res() + "p");
            }
        });
        updateResLabel();
        addRow(resBtn);
        addCheck("화면 구석에 프레임(fps) 표시", "fps", false);
        addCheck("시작할 때 USB를 자동으로 불러와 랜덤 재생", "autostart", true);
        addCheck("랜덤(셔플) 재생", "shuffle", true);

        addHeader("배경 효과 (곡마다 1개 랜덤)");
        for (Fx.Def d : Fx.BG) addCheck(d.name, d.key, true);
        addHeader("가사가 나타나는 효과 (곡마다 2~3개를 섞음)");
        for (Fx.Def d : Fx.ENTER) addCheck(d.name, d.key, true);
        addHeader("가사가 사라지는 효과 (곡마다 1개)");
        for (Fx.Def d : Fx.EXIT) addCheck(d.name, d.key, true);
        addHeader("강조 장식 (가끔, 한 줄에 단어 하나)");
        for (Fx.Def d : Fx.DECO) addCheck(d.name, d.key, true);

        addRow(button("효과 모두 켜기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllEffects(true);
            }
        }));
        addRow(button("효과 모두 끄기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllEffects(false);
            }
        }));
        addRow(button("닫기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSettings();
            }
        }));
        root.addView(settingsPanel, new FrameLayout.LayoutParams(dp(620), MATCH, Gravity.END));
    }

    private void addRow(View v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.setMargins(0, dp(8), 0, 0);
        settingsBox.addView(v, p);
    }

    private void addHeader(String s) {
        TextView t = text(s, 20, 0xFF9AD0FF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, WRAP);
        p.setMargins(0, dp(20), 0, dp(4));
        settingsBox.addView(t, p);
    }

    private void addCheck(String label, final String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        cb.setChecked(prefs.get(key, def));
        cb.setPadding(dp(8), dp(8), dp(8), dp(8));
        styleFocus(cb);
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.set(key, v);
                if (key.equals("fps")) updateInfo();
            }
        });
        addRow(cb);
    }

    private void setAllEffects(boolean v) {
        for (int i = 0; i < settingsBox.getChildCount(); i++) {
            View c = settingsBox.getChildAt(i);
            if (c instanceof CheckBox) {
                CheckBox cb = (CheckBox) c;
                String t = cb.getText().toString();
                if (isEffectLabel(t)) cb.setChecked(v);
            }
        }
        toast(v ? "효과를 모두 켰습니다" : "효과를 모두 껐습니다");
    }

    private boolean isEffectLabel(String t) {
        for (Fx.Def d : Fx.BG) if (d.name.equals(t)) return true;
        for (Fx.Def d : Fx.ENTER) if (d.name.equals(t)) return true;
        for (Fx.Def d : Fx.EXIT) if (d.name.equals(t)) return true;
        for (Fx.Def d : Fx.DECO) if (d.name.equals(t)) return true;
        return false;
    }

    private void updateResLabel() {
        resBtn.setText("그리는 해상도: " + prefs.res() + "p (누르면 " + (prefs.res() == 720 ? 1080 : 720) + "p로 변경)");
    }

    private void applyResolution() {
        int r = prefs.res();
        glView.getHolder().setFixedSize(r == 1080 ? 1920 : 1280, r == 1080 ? 1080 : 720);
    }

    // ------------------------------------------------------------ View 도우미
    private TextView text(String s, float sizeSp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        styleFocus(b);
        b.setOnClickListener(l);
        return b;
    }

    /** 포커스된 항목은 흰 바탕에 검은 글자로 뚜렷하게 */
    private void styleFocus(TextView v) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(Color.WHITE));
        sd.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.WHITE));
        sd.addState(new int[]{}, new ColorDrawable(0x33FFFFFF));
        v.setBackground(sd);
        v.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{Color.BLACK, Color.BLACK, Color.WHITE}));
    }

    private void toast(String s) {
        toastView.setText(s);
        toastView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 1600);
    }

    private final Runnable hideToast = new Runnable() {
        @Override
        public void run() {
            toastView.setVisibility(View.GONE);
        }
    };

    private final Runnable hideCtrl = new Runnable() {
        @Override
        public void run() {
            ctrlPanel.setVisibility(View.GONE);
            refreshNow();
        }
    };

    private boolean panelOpen() {
        return startPanel.getVisibility() == View.VISIBLE || ctrlPanel.getVisibility() == View.VISIBLE
                || settingsPanel.getVisibility() == View.VISIBLE;
    }

    private void showCtrl() {
        if (!started) return;
        ctrlPanel.setVisibility(View.VISIBLE);
        refreshNow();
        playBtn.requestFocus();
        armCtrlHide();
    }

    private void armCtrlHide() {
        ui.removeCallbacks(hideCtrl);
        if (ctrlPanel.getVisibility() == View.VISIBLE) ui.postDelayed(hideCtrl, 8000);
    }

    private void toggleSettings() {
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            refreshNow();
            return;
        }
        ctrlPanel.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.VISIBLE);
        refreshNow();
        resBtn.requestFocus();
    }

    private void updatePlayLabel() {
        if (playBtn != null) playBtn.setText(player != null && player.isPlaying() ? "정지" : "재생");
    }

    private void updateInfo() {
        boolean show = prefs.get("fps", false);
        fpsView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) fpsView.setText(prefs.res() + "p · " + renderer.fps + " fps");
        if (ctrlPanel.getVisibility() == View.VISIBLE && started) {
            timeView.setText(fmt(player.getCurrentPosition()) + " / " + fmt(player.getDuration()));
        }
        refreshNow();
    }

    /** 재생 중 하단에 "제목 - 가수명"을 계속 띄운다. 컨트롤·목록·설정이 열려 있으면 겹치지 않게 숨긴다. */
    private void refreshNow() {
        boolean show = started && curLabel.length() > 0 && startPanel.getVisibility() != View.VISIBLE
                && ctrlPanel.getVisibility() != View.VISIBLE && settingsPanel.getVisibility() != View.VISIBLE;
        nowView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setLabel(String s) {
        curLabel = s == null ? "" : s;
        nowView.setText(curLabel);
        titleView.setText(curLabel);
        refreshNow();
    }

    private static String label(String title, String artist) {
        return artist == null || artist.length() == 0 ? title : title + " - " + artist;
    }

    private static String nz(String a, String b) {
        return a != null && a.trim().length() > 0 ? a.trim() : b;
    }

    /** 파일 이름이 "가수 - 제목" 형태면 나눠서 {제목, 가수}, 아니면 {파일 이름, null} */
    private static String[] guessNames(Track t) {
        String base = t.title;
        int i = base.indexOf(" - ");
        if (i > 0 && i + 3 < base.length()) {
            return new String[]{base.substring(i + 3).trim(), base.substring(0, i).trim()};
        }
        return new String[]{base, null};
    }

    /** 파일 태그의 {제목, 가수}. 못 읽으면 null 들. */
    private static String[] readTags(Track t) {
        String[] r = new String[2];
        MediaMetadataRetriever mm = new MediaMetadataRetriever();
        try {
            mm.setDataSource(t.audio.getAbsolutePath());
            r[0] = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            r[1] = mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        } catch (Throwable e) {
            // 태그를 못 읽으면 파일 이름을 쓴다
        } finally {
            try {
                mm.release();
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ------------------------------------------------------------ 리모컨
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int rep = e.getRepeatCount();
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && rep == 0) onBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_SETTINGS:
                if (down && rep == 0) toggleSettings();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down && rep == 0 && started) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && rep == 0 && started && !player.isPlaying()) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down && rep == 0 && started && player.isPlaying()) togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && rep == 0) step(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && rep == 0) step(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seekBy(rep > 3 ? 60000 : 20000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) seekBy(rep > 3 ? -60000 : -20000);
                return true;
            default:
                break;
        }
        if (panelOpen() || !started) {
            armCtrlHide();
            return super.dispatchKeyEvent(e); // 메뉴가 열려 있으면 방향키는 기본 포커스 이동
        }
        // 재생 화면: 좌우 = 이전/다음 곡, 상하 = 컨트롤 열기, 확인 = 재생/정지
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && rep == 0) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && rep == 0) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && rep == 0) showCtrl();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && rep == 0) {
                    togglePlay();
                    toast(player.isPlaying() ? "▶ 재생" : "❚❚ 일시정지");
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onBack() {
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            refreshNow();
            return;
        }
        if (ctrlPanel.getVisibility() == View.VISIBLE) {
            ctrlPanel.setVisibility(View.GONE);
            refreshNow();
            return;
        }
        if (startPanel.getVisibility() == View.VISIBLE && started) {
            startPanel.setVisibility(View.GONE);
            refreshNow();
            return;
        }
        long n = SystemClock.uptimeMillis();
        if (n - backT < 2500) {
            finishAndRemoveTask();
            return;
        }
        backT = n;
        toast("뒤로 버튼을 한 번 더 누르면 종료합니다");
    }

    // ------------------------------------------------------------ USB 검색
    private void scan() {
        if (scanning) return;
        scanning = true;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        runScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_STORAGE) runScan();
    }

    private void runScan() {
        statusView.setText("USB 검색 중…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FileScanner.Result r = FileScanner.scan(getApplicationContext(), new FileScanner.Progress() {
                    @Override
                    public void on(final int n) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                statusView.setText("USB 검색 중… " + n + "곡");
                            }
                        });
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onScanned(r);
                    }
                });
            }
        }, "usb-scan").start();
    }

    private void onScanned(FileScanner.Result r) {
        scanning = false;
        if (started && r.tracks.isEmpty()) { // 재생 중에 다시 불러왔는데 아무것도 못 찾으면 기존 목록을 그대로 둔다
            statusView.setText(r.note);
            toast(r.note);
            return;
        }
        tracks.clear();
        tracks.addAll(r.tracks);
        names.clear();
        int withLrc = 0;
        for (Track t : tracks) {
            names.add(t.title + (t.lrc != null ? "   ♪" : ""));
            if (t.lrc != null) withLrc++;
        }
        adapter.notifyDataSetChanged();
        statusView.setText(tracks.isEmpty() ? r.note : "곡 " + tracks.size() + "개 · 가사 " + withLrc + "개");
        if (!tracks.isEmpty()) {
            boolean auto = firstScan && !started && prefs.get("autostart", true);
            if (auto || started) { // 재생 중에 다시 불러오면 새 목록으로 랜덤 재생을 다시 시작(예전 순서 번호가 어긋나는 것을 막음)
                firstScan = false;
                startPlayback(-1);
                return;
            }
            listView.requestFocus();
        } else {
            usbBtn.requestFocus();
        }
        firstScan = false;
    }

    // ------------------------------------------------------------ 재생
    private void startPlayback(int startIdx) {
        int n = tracks.size();
        if (n == 0) return;
        ArrayList<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(i);
        if (prefs.get("shuffle", true)) {
            Collections.shuffle(l, rnd);
            if (startIdx >= 0) {
                l.remove(Integer.valueOf(startIdx));
                l.add(0, startIdx);
            }
        } else if (startIdx > 0) {
            Collections.rotate(l, -startIdx);
        }
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = l.get(i);
        pos = 0;
        started = true;
        startPanel.setVisibility(View.GONE);
        playCurrent();
    }

    private void step(int d) {
        if (!started || order.length == 0) return;
        pos = (pos + d + order.length) % order.length;
        playCurrent();
    }

    private void playCurrent() {
        final Track t = tracks.get(order[pos]);
        final int g = ++loadGen;
        final Fx.Song song = Fx.create(prefs, t.title, hist, rnd);
        renderer.posMs = 0;
        final String[] gn = guessNames(t);
        setLabel(label(gn[0], gn[1]));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Lrc.Line> ls = readLines(t);
                String[] tg = readTags(t);
                final String lb = label(nz(tg[0], gn[0]), nz(tg[1], gn[1]));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (g == loadGen) {
                            renderer.setTrack(song, ls);
                            setLabel(lb);
                        }
                    }
                });
            }
        }, "lrc-load").start();
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(t.audio)));
        player.prepare();
        player.play();
        updatePlayLabel();
    }

    private void togglePlay() {
        if (!started) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        updatePlayLabel();
    }

    private void seekBy(long ms) {
        if (!started) return;
        long to = Math.max(0, player.getCurrentPosition() + ms);
        long dur = player.getDuration();
        if (dur > 0) to = Math.min(to, dur - 500);
        player.seekTo(Math.max(0, to));
        toast((ms > 0 ? "▶▶ " : "◀◀ ") + fmt(to));
    }

    private List<Lrc.Line> readLines(Track t) {
        List<Lrc.Line> out = new ArrayList<>();
        if (t.lrc != null) {
            try {
                out = Lrc.parse(Lrc.decode(readAll(t.lrc, 1 << 20)));
            } catch (Throwable e) {
                out = new ArrayList<>();
            }
        }
        return out;
    }

    private static byte[] readAll(File f, int cap) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < cap) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------ 그리기 틱 (30fps)
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (started) renderer.posMs = player.getCurrentPosition();
            glView.requestRender();
            long now = SystemClock.uptimeMillis();
            if (now - lastInfo >= 500) {
                lastInfo = now;
                updateInfo();
            }
            ui.postDelayed(this, 33);
        }
    };

    // ------------------------------------------------------------ 수명 주기
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        if (renderer != null) renderer.release();
        boolean fin = isFinishing();
        super.onDestroy();
        if (fin) Process.killProcess(Process.myPid());
    }
}

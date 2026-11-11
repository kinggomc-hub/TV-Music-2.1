package com.lyricplayer.tvn;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 배경(곡마다 셰이더 1종)과 가사(줄 비트맵을 텍스처로 올려 GPU 가 이동/확대/투명도만 처리)를 그린다.
 * 그리는 일은 모두 GL 스레드에서 하고, 글자 비트맵 만들기만 별도 스레드에서 미리 해 둔다.
 * 프래그먼트 셰이더는 mediump 만 쓴다(오래된 Mali 는 highp 미지원). 시간은 CPU 에서 0~2π 로 접어서 넘긴다.
 */
final class GlRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "LyricGL";
    private static final double TWO_PI = Math.PI * 2.0;

    // ------------------------------------------------------------ 셰이더
    private static final String VS_FULL =
            "attribute vec2 aPos;\nvarying vec2 vUV;\n"
                    + "void main(){gl_Position=vec4(aPos,0.,1.);vUV=aPos*.5+.5;}\n";

    private static final String FS_HEAD =
            "precision mediump float;\nvarying vec2 vUV;\n"
                    + "uniform vec4 uPh;\nuniform vec2 uSeed;\nuniform float uHue;\nuniform vec4 uM;\nuniform float uMA;\n"
                    + "vec3 hsv(float h,float s,float v){vec3 k=clamp(abs(fract(h+vec3(0.,.6667,.3333))*6.-3.)-1.,0.,1.);return v*mix(vec3(1.),k,s);}\n"
                    + "float fall(float r,float d){return 1.-smoothstep(0.,r,d);}\n"
                    + "vec3 base(vec2 uv){\n"
                    + " vec3 c=hsv(uHue,.55,.30)*fall(.75,length((uv-vec2(.5,.42))*vec2(1.,.75)));\n"
                    + " c+=hsv(uHue+.11,.5,.22)*fall(.6,length((uv-vec2(.88,0.))*vec2(1.,.8)));\n"
                    + " c*=1.-.5*smoothstep(.55,1.,abs(uv.y-.5)*2.);\n"
                    + " return c;}\n"
                    + "void main(){vec2 uv=vUV;vec3 c=base(uv);\n";
    private static final String FS_TAIL = "gl_FragColor=vec4(c,1.);}\n";

    /** 배경 번호별 효과 본문. -1 과 점 효과(0,6)는 기본 그라데이션만. */
    private static String body(int id) {
        switch (id) {
            case 1: // 숨 쉬는 빛
                return "float b=.5+.5*sin(uPh.x);\n"
                        + "c*=.80+.35*b;\n"
                        + "c+=hsv(uHue+.04,.5,.16)*b*fall(.55,length((uv-vec2(.5,.45))*vec2(1.2,1.)));\n";
            case 2: // 떠다니는 빛 덩어리
                return "for(int i=0;i<5;i++){\n"
                        + " float fi=float(i);\n"
                        + " vec2 p=vec2(.5+.34*sin(uPh.x*(1.+mod(fi,2.))+fi*1.9+uSeed.x*6.28),.5+.26*sin(uPh.y*(1.+mod(fi+1.,2.))+fi*2.7+uSeed.y*6.28));\n"
                        + " float r=.20+.04*sin(uPh.z+fi*1.3);\n"
                        + " float d=length((uv-p)*vec2(1.78,1.));\n"
                        + " c+=hsv(uHue+.06*fi,.45,.20)*fall(r,d)*.7;\n"
                        + "}\n";
            case 3: // 흐르는 그라데이션
                return "float w=.5+.5*sin(uv.x*3.+uPh.x+sin(uv.y*2.+uPh.y)*1.2);\n"
                        + "c=mix(hsv(uHue,.5,.08),hsv(uHue+.12,.55,.28),w)*(.65+.35*(1.-smoothstep(.2,.9,abs(uv.y-.5)*2.)));\n";
            case 4: // 지나가는 빛줄기
                return "vec2 d=(uv-vec2(.85,1.15))*vec2(1.78,1.);\n"
                        + "float a=atan(d.y,d.x);\n"
                        + "float r=.5+.5*sin(a*12.+uPh.x*2.);\n"
                        + "r=r*r*r;\n"
                        + "c+=hsv(uHue+.03,.3,.24)*r*fall(1.7,length(d))*.55;\n";
            case 5: // 가끔 지나가는 유성
                return "vec2 p=(uv-uM.xy)*vec2(1.78,1.);\n"
                        + "vec2 tdir=-uM.zw;\n"
                        + "float L=.14;\n"
                        + "float t=clamp(dot(p,tdir),0.,L);\n"
                        + "float d=length(p-tdir*t);\n"
                        + "c+=vec3(.9,.95,1.)*fall(.007,d)*(1.-t/L)*uMA;\n";
            case 7: // 은은한 가로 파동선
                return "for(int i=0;i<4;i++){\n"
                        + " float fi=float(i);\n"
                        + " float y=.22+.15*fi+.025*sin(uv.x*(5.+2.*fi)+uPh.x*(1.+mod(fi,2.))+fi*1.7+uSeed.x*6.28);\n"
                        + " c+=hsv(uHue+.05*fi,.4,.22)*fall(.007,abs(uv.y-y))*.8;\n"
                        + "}\n";
            case 8: // 테두리 어둠이 숨 쉼
                return "float b=.5+.5*sin(uPh.x);\n"
                        + "float v=length((uv-.5)*vec2(1.25,1.));\n"
                        + "c*=1.-(.30+.35*b)*smoothstep(.30,.80,v);\n"
                        + "c+=hsv(uHue,.4,.05)*b;\n";
            case 9: // 오로라 커튼: 세로로 길고 흐린 빛 띠 3개가 천천히 일렁임
                return "for(int i=0;i<3;i++){\n"
                        + " float fi=float(i);\n"
                        + " float x=.2+.3*fi+.07*sin(uPh.x*(1.+mod(fi,2.))+fi*2.1+uSeed.x*6.28);\n"
                        + " float w=.12+.03*sin(uPh.y+fi*1.7);\n"
                        + " float band=fall(w,abs(uv.x-x+.05*sin(uv.y*4.+uPh.z*(1.+mod(fi+1.,2.))+fi)));\n"
                        + " float v=smoothstep(.1,.55,uv.y)*(1.-smoothstep(.75,1.,uv.y));\n"
                        + " c+=hsv(uHue+.1*fi+.05,.55,.26)*band*v*.9;\n"
                        + "}\n";
            case 10: // 노을빛 띠: 가로로 넓고 흐린 색 띠 3개가 천천히 위아래로
                return "for(int i=0;i<3;i++){\n"
                        + " float fi=float(i);\n"
                        + " float y=.25+.25*fi+.05*sin(uPh.x*(1.+mod(fi,2.))+fi*1.9+uSeed.y*6.28);\n"
                        + " c+=hsv(uHue+.08*fi,.5,.22)*fall(.22,abs(uv.y-y))*.8;\n"
                        + "}\n";
            case 11: // 깊이감 빛 덩어리: 크고 흐린 먼 겹 + 작고 밝은 가까운 겹
                return "for(int i=0;i<4;i++){\n"
                        + " float fi=float(i);\n"
                        + " vec2 p=vec2(.5+.40*sin(uPh.z*(1.+mod(fi,2.))+fi*1.7+uSeed.x*6.28),.5+.30*sin(uPh.w*(1.+mod(fi+1.,2.))+fi*2.3+uSeed.y*6.28));\n"
                        + " c+=hsv(uHue+.04*fi,.4,.14)*fall(.30,length((uv-p)*vec2(1.78,1.)))*.8;\n"
                        + "}\n"
                        + "for(int j=0;j<3;j++){\n"
                        + " float fj=float(j);\n"
                        + " vec2 q=vec2(.5+.32*sin(uPh.x*(1.+mod(fj,2.))+fj*2.4+uSeed.y*6.28),.5+.24*sin(uPh.y*(1.+mod(fj+1.,2.))+fj*1.6+uSeed.x*6.28));\n"
                        + " c+=hsv(uHue+.09*fj+.1,.45,.24)*fall(.14,length((uv-q)*vec2(1.78,1.)))*.7;\n"
                        + "}\n";
            case 12: // 안개 흐름: 큰 타원 3개가 천천히 흘러가며 색이 섞임
                return "for(int i=0;i<3;i++){\n"
                        + " float fi=float(i);\n"
                        + " vec2 p=vec2(.5+.30*sin(uPh.w*(1.+mod(fi,2.))+fi*2.0+uSeed.x*6.28),.5+.20*sin(uPh.z+fi*1.3));\n"
                        + " vec2 d=(uv-p)*vec2(.9,1.6);\n"
                        + " c+=hsv(uHue+.07*fi,.35,.18)*fall(.55,length(d))*.9;\n"
                        + "}\n";
            case 13: // 스포트라이트 스윕: 넓고 부드러운 빛줄기가 좌우로 아주 천천히
                return "float ang=.35*sin(uPh.w);\n"
                        + "vec2 d=(uv-vec2(.5,1.1))*vec2(1.78,1.);\n"
                        + "float a=atan(d.x,-d.y);\n"
                        + "float beam=fall(.28,abs(a-ang));\n"
                        + "c+=hsv(uHue+.02,.25,.30)*beam*fall(1.5,length(d))*.55;\n";
            case 14: // 대각선 리본
                return "for(int i=0;i<2;i++){\n"
                        + " float fi=float(i);\n"
                        + " float t=uv.x*.6+uv.y;\n"
                        + " float c0=.45+.45*fi+.12*sin(uPh.y*(1.+fi)+fi*2.0+uSeed.x*6.28);\n"
                        + " c+=hsv(uHue+.1*fi,.45,.22)*fall(.16,abs(t-c0))*.9;\n"
                        + "}\n";
            case 15: // 공전하는 빛: 큰 빛 하나가 화면을 크게 돌며 색이 조금씩 변함
                return "vec2 p=vec2(.5+.28*cos(uPh.w),.5+.18*sin(uPh.w));\n"
                        + "float d=length((uv-p)*vec2(1.78,1.));\n"
                        + "c+=hsv(uHue+.04*sin(uPh.z),.5,.30)*fall(.45,d)*.8;\n";
            default:
                return "";
        }
    }

    private static final String VS_PTS =
            "attribute vec4 aP;\nuniform vec4 uPh;\nuniform float uScale;\nuniform float uMode;\nuniform float uRise;\nvarying float vA;\n"
                    + "void main(){\n"
                    + " vec2 p=aP.xy;float a;\n"
                    + " if(uMode<.5){a=.15+.85*(.5+.5*sin(uPh.x*2.+aP.w*6.2832));}\n"
                    + " else{p.y=fract(aP.y*.5+.5+uRise)*2.-1.;p.x+=.02*sin(uPh.y+aP.w*6.2832);a=.45*(1.-abs(p.y));}\n"
                    + " gl_Position=vec4(p,0.,1.);gl_PointSize=aP.z*uScale;vA=a;}\n";
    private static final String FS_PTS =
            "precision mediump float;\nvarying float vA;\nuniform vec3 uCol;\n"
                    + "void main(){float d=length(gl_PointCoord-vec2(.5));float a=(1.-smoothstep(.05,.5,d))*vA;gl_FragColor=vec4(uCol*a,a);}\n";

    private static final String VS_TXT =
            "attribute vec2 aPos;\nattribute vec2 aUV;\nvarying vec2 vUV;\n"
                    + "void main(){gl_Position=vec4(aPos,0.,1.);vUV=aUV;}\n";
    private static final String FS_TXT =
            "precision mediump float;\nvarying vec2 vUV;\nuniform sampler2D uTex;\nuniform float uA;\nuniform vec4 uClip;\n"
                    + "void main(){if(vUV.x<uClip.x||vUV.x>uClip.y||vUV.y<uClip.z||vUV.y>uClip.w)discard;gl_FragColor=texture2D(uTex,vUV)*uA;}\n";

    private static final class Prog {
        int id;
        int aPos = -1, uPh = -1, uSeed = -1, uHue = -1, uM = -1, uMA = -1;
    }

    private static final class Pose {
        float dx, dy, scale = 1f, sx = 1f, sy = 1f, rot, alpha = 1f, u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;

        void reset() {
            dx = 0f;
            dy = 0f;
            scale = 1f;
            sx = 1f;
            sy = 1f;
            rot = 0f;
            alpha = 1f;
            u0 = 0f;
            u1 = 1f;
            v0 = 0f;
            v1 = 1f;
        }
    }

    // ------------------------------------------------------------ 상태
    private final GLSurfaceView view;
    volatile long posMs;          // 재생 위치 (메인 스레드가 갱신)
    volatile int fps;             // 최근 1초 프레임 수
    private volatile int gen = 0; // 곡이 바뀔 때마다 증가: 오래된 미리 만들기 결과를 버리는 용도

    private boolean glReady = false;
    private int vw = 1280, vh = 720;
    private long t0 = 0, fpsT = 0;
    private int frames = 0;

    private final HashMap<Integer, Prog> progs = new HashMap<>();
    private Prog txt;
    private int txtTex = -1, txtA = -1, txtClip = -1, txtUV = -1;
    private int ptsId = 0, ptsP = -1, ptsPh = -1, ptsScale = -1, ptsMode = -1, ptsRise = -1, ptsCol = -1;

    private final FloatBuffer fullBuf;
    private final FloatBuffer posBuf, uvBuf;
    private final float[] qp = new float[8], qt = new float[8];
    private final float[] lx = new float[4], ly = new float[4];
    private final Pose pose = new Pose();

    // 점 효과(별, 먼지)
    private FloatBuffer ptsBuf;
    private int ptsN = 0, ptsBuilt = -1;
    private Fx.Song ptsSong;

    // 유성
    private double meteorNext = 0, meteorStart = -10;
    private float mX, mY, mDx, mDy, mA;
    private final Random mr = new Random();

    // 곡과 가사
    private volatile Fx.Song song;
    private volatile Lrc.Line[] lines = new Lrc.Line[0];
    private int shown = -2;
    private LineTex cur, exiting;
    private long curStart, exitStart;
    private int exitId = 0;

    private final ConcurrentHashMap<Integer, LineTex> ready = new ConcurrentHashMap<>();
    private final Set<Integer> pending = Collections.synchronizedSet(new HashSet<Integer>());
    private final HandlerThread prepThread = new HandlerThread("lyric-prep");
    private Handler prep;

    GlRenderer(GLSurfaceView view) {
        this.view = view;
        fullBuf = floats(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
        posBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        uvBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        prepThread.start();
        prep = new Handler(prepThread.getLooper());
    }

    private static FloatBuffer floats(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    // ------------------------------------------------------------ 바깥(메인 스레드)에서 부르는 함수
    /** 새 곡: 효과 묶음과 가사 줄을 넘긴다. */
    void setTrack(final Fx.Song s, final List<Lrc.Line> ls) {
        final Lrc.Line[] arr = ls.toArray(new Lrc.Line[0]);
        view.queueEvent(new Runnable() {
            @Override
            public void run() {
                gen++;
                clearReady();
                freeLines();
                song = s;
                lines = arr;
                shown = -2;
                ptsBuilt = -1;
                meteorNext = 0;
                meteorStart = -10;
            }
        });
    }

    void release() {
        prepThread.quitSafely();
    }

    // ------------------------------------------------------------ GLSurfaceView.Renderer
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        glReady = true;
        progs.clear();
        // 새 GL 컨텍스트: 이전 텍스처는 이미 사라졌다
        cur = null;
        exiting = null;
        clearReady();
        shown = -2;
        ptsBuilt = -1;
        txt = new Prog();
        txt.id = program(VS_TXT, FS_TXT);
        if (txt.id != 0) {
            txt.aPos = GLES20.glGetAttribLocation(txt.id, "aPos");
            txtUV = GLES20.glGetAttribLocation(txt.id, "aUV");
            txtTex = GLES20.glGetUniformLocation(txt.id, "uTex");
            txtA = GLES20.glGetUniformLocation(txt.id, "uA");
            txtClip = GLES20.glGetUniformLocation(txt.id, "uClip");
        }
        ptsId = program(VS_PTS, FS_PTS);
        if (ptsId != 0) {
            ptsP = GLES20.glGetAttribLocation(ptsId, "aP");
            ptsPh = GLES20.glGetUniformLocation(ptsId, "uPh");
            ptsScale = GLES20.glGetUniformLocation(ptsId, "uScale");
            ptsMode = GLES20.glGetUniformLocation(ptsId, "uMode");
            ptsRise = GLES20.glGetUniformLocation(ptsId, "uRise");
            ptsCol = GLES20.glGetUniformLocation(ptsId, "uCol");
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        vw = Math.max(1, width);
        vh = Math.max(1, height);
        GLES20.glViewport(0, 0, vw, vh);
        // 해상도가 바뀌면 글자 비트맵 크기도 달라지므로 다시 만든다
        gen++;
        clearReady();
        freeLines();
        shown = -2;
        ptsBuilt = -1;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        if (t0 == 0) {
            t0 = now;
            fpsT = now;
        }
        double t = (now - t0) / 1e9;
        frames++;
        if (now - fpsT >= 1000000000L) {
            fps = frames;
            frames = 0;
            fpsT = now;
        }
        drawBackground(t);
        drawLyrics(now);
    }

    // ------------------------------------------------------------ 배경
    private static float wrap(double x) {
        return (float) (x % TWO_PI);
    }

    private Prog fs(int id) {
        Prog p = progs.get(id);
        if (p == null) {
            p = new Prog();
            p.id = program(VS_FULL, FS_HEAD + body(id) + FS_TAIL);
            if (p.id != 0) {
                p.aPos = GLES20.glGetAttribLocation(p.id, "aPos");
                p.uPh = GLES20.glGetUniformLocation(p.id, "uPh");
                p.uSeed = GLES20.glGetUniformLocation(p.id, "uSeed");
                p.uHue = GLES20.glGetUniformLocation(p.id, "uHue");
                p.uM = GLES20.glGetUniformLocation(p.id, "uM");
                p.uMA = GLES20.glGetUniformLocation(p.id, "uMA");
            }
            progs.put(id, p);
        }
        return p;
    }

    private void drawBackground(double t) {
        Fx.Song s = song;
        float hue = s != null ? s.hue : 0.6f, spd = s != null ? s.spd : 1f;
        float sx = s != null ? s.seedX : 0.3f, sy = s != null ? s.seedY : 0.6f;
        int bg = s != null ? s.bg : -1;
        boolean pts = (bg == 0 || bg == 6);
        Prog pr = fs(pts ? -1 : bg);
        if (pr.id == 0) pr = fs(-1);
        if (pr.id == 0) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        float p0 = wrap(t * 0.30 * spd), p1 = wrap(t * 0.21 * spd), p2 = wrap(t * 0.13 * spd), p3 = wrap(t * 0.09 * spd);
        if (bg == 5) updateMeteor(t);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(pr.id);
        GLES20.glUniform4f(pr.uPh, p0, p1, p2, p3);
        GLES20.glUniform2f(pr.uSeed, sx, sy);
        GLES20.glUniform1f(pr.uHue, hue);
        GLES20.glUniform4f(pr.uM, mX, mY, mDx, mDy);
        GLES20.glUniform1f(pr.uMA, mA);
        GLES20.glEnableVertexAttribArray(pr.aPos);
        GLES20.glVertexAttribPointer(pr.aPos, 2, GLES20.GL_FLOAT, false, 0, fullBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pr.aPos);
        if (pts && ptsId != 0) drawPoints(bg == 0 ? 0 : 1, p0, p1, p2, p3, t, spd, s);
    }

    private void updateMeteor(double t) {
        if (t >= meteorNext) {
            meteorStart = t;
            meteorNext = t + 4.0 + mr.nextFloat() * 5.0;
            mX = 0.45f + 0.5f * mr.nextFloat();
            mY = 0.62f + 0.33f * mr.nextFloat();
            double a = 0.35 + 0.35 * mr.nextFloat();
            mDx = (float) -Math.cos(a);
            mDy = (float) -Math.sin(a);
            startX = mX;
            startY = mY;
        }
        double p = (t - meteorStart) / 1.1;
        if (p < 0 || p > 1) {
            mA = 0f;
        } else {
            mA = (float) Math.sin(Math.PI * p);
            mX = startX + (float) (mDx / 1.78 * p * 0.55);
            mY = startY + (float) (mDy * p * 0.55);
        }
    }

    private float startX, startY;

    private void drawPoints(int mode, float p0, float p1, float p2, float p3, double t, float spd, Fx.Song s) {
        if (ptsBuilt != mode || ptsSong != s) buildPoints(mode, s);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glUseProgram(ptsId);
        GLES20.glUniform4f(ptsPh, p0, p1, p2, p3);
        GLES20.glUniform1f(ptsScale, vh / 720f);
        GLES20.glUniform1f(ptsMode, (float) mode);
        GLES20.glUniform1f(ptsRise, (float) ((t * 0.02 * spd) % 1.0));
        GLES20.glUniform3f(ptsCol, 0.85f, 0.9f, 1f);
        GLES20.glEnableVertexAttribArray(ptsP);
        GLES20.glVertexAttribPointer(ptsP, 4, GLES20.GL_FLOAT, false, 16, ptsBuf);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ptsN);
        GLES20.glDisableVertexAttribArray(ptsP);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void buildPoints(int mode, Fx.Song s) {
        int n = mode == 0 ? 80 : 30;
        Random r = new Random(s != null ? Float.floatToIntBits(s.seedX) : 7);
        float[] a = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float q = r.nextFloat();
            a[i * 4] = r.nextFloat() * 2f - 1f;
            a[i * 4 + 1] = r.nextFloat() * 2f - 1f;
            a[i * 4 + 2] = mode == 0 ? 2f + 3f * q * q : 3f + 4f * q;
            a[i * 4 + 3] = r.nextFloat();
        }
        ptsBuf = floats(a);
        ptsN = n;
        ptsBuilt = mode;
        ptsSong = s;
    }

    // ------------------------------------------------------------ 가사
    private int find(long pos) {
        Lrc.Line[] L = lines;
        int lo = 0, hi = L.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (L[mid].ms <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void drawLyrics(long now) {
        if (txt == null || txt.id == 0) return;
        int want = find(posMs);
        if (want != shown) switchTo(want, now);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(txt.id);
        GLES20.glEnableVertexAttribArray(txt.aPos);
        GLES20.glEnableVertexAttribArray(txtUV);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(txtTex, 0);

        if (exiting != null) drawExiting(now);
        if (cur != null) drawEntering(now);

        GLES20.glDisableVertexAttribArray(txt.aPos);
        GLES20.glDisableVertexAttribArray(txtUV);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void switchTo(int want, long now) {
        if (cur != null) {
            if (song != null && song.exit >= 0) {
                if (exiting != null) free(exiting);
                exiting = cur;
                exitStart = now;
                exitId = song.exit;
            } else {
                free(cur);
            }
            cur = null;
        }
        shown = want;
        Lrc.Line[] L = lines;
        if (want >= 0 && want < L.length) {
            String tx = L[want].text;
            if (tx != null && tx.length() > 0) {
                LineTex lt = ready.remove(want);
                if (lt == null) lt = buildFor(song, L[want], want, vw, vh);
                if (lt != null) {
                    upload(lt);
                    cur = lt;
                    curStart = now;
                }
            }
            prefetch(want + 1);
        }
    }

    private void drawEntering(long now) {
        LineTex lt = cur;
        int id = lt.enter;
        if (id >= Fx.SEG_FIRST && lt.segs.length == 0) id = 0;
        float el = (now - curStart) / 1e6f;
        int dir = song != null ? song.dir : 1;
        boolean seg = id >= Fx.SEG_FIRST;
        int dur = id < 0 ? 0 : (seg ? segTotal(lt) : enterDur(id));
        pose.reset();
        if (id >= 0 && el < dur) {
            if (seg) {
                drawSegments(lt, el, id, dir);
                return;
            }
            enterPose(id, el / dur, lt, dir, pose);
        }
        drawWhole(lt, pose);
    }

    private static int enterDur(int id) {
        switch (id) {
            case 0:
            case 1:
                return 400;
            case 2:
            case 3:
                return 360;
            case 4:
            case 8:
            case 10:
            case 11:
                return 380;
            case 9:
                return 400;
            default:
                return 420;
        }
    }

    private static float segStag(int n) {
        return n > 1 ? Math.min(30f, 200f / (n - 1)) : 0f;
    }

    private static int segTotal(LineTex lt) {
        int n = lt.segs.length;
        return (int) ((n - 1) * segStag(n) + 200f);
    }

    private static float easeOut(float p) {
        double q = 1.0 - Math.max(0f, Math.min(1f, p));
        return (float) (1.0 - q * q * q);
    }

    private void enterPose(int id, float p, LineTex lt, int dir, Pose o) {
        float e = easeOut(p);
        switch (id) {
            case 0:
                o.dy = 0.22f * lt.fontPx * (1f - e);
                o.alpha = e;
                break;
            case 1:
                o.dy = -0.22f * lt.fontPx * (1f - e);
                o.alpha = e;
                break;
            case 2:
                o.scale = 0.96f + 0.04f * e;
                o.alpha = e;
                break;
            case 3:
                o.scale = 1.04f - 0.04f * e;
                o.alpha = e;
                break;
            case 4:
                o.dx = dir * 0.025f * vw * (1f - e);
                o.alpha = e;
                break;
            case 5:
                o.u1 = e;
                break;
            case 6:
                o.u0 = 0.5f - 0.5f * e;
                o.u1 = 0.5f + 0.5f * e;
                break;
            case 7:
                o.v1 = e;
                break;
            case 8:
                o.rot = (float) Math.toRadians(dir * 1.6f * (1f - e));
                o.alpha = e;
                break;
            case 9: // 대각선으로 살짝 들어옴
                o.dx = dir * 0.02f * vw * (1f - e);
                o.dy = 0.12f * lt.fontPx * (1f - e);
                o.alpha = e;
                break;
            case 10: // 가로로 펼쳐짐
                o.sx = 0.90f + 0.10f * e;
                o.alpha = e;
                break;
            case 11: // 세로로 펼쳐짐
                o.sy = 0.85f + 0.15f * e;
                o.alpha = e;
                break;
            case 12: // 아래에서 위로 드러남
                o.v0 = 1f - e;
                break;
            case 13: // 오른쪽에서 왼쪽으로 드러남
                o.u0 = 1f - e;
                break;
            default:
                break;
        }
    }

    private void drawSegments(LineTex lt, float el, int id, int dir) {
        int n = lt.segs.length;
        float stag = segStag(n);
        for (int k = 0; k < n; k++) {
            float[] s = lt.segs[k];
            float local = (el - lt.rank[k] * stag) / 200f;
            if (local <= 0f) continue; // 아직 나타날 차례가 아님
            float e = easeOut(local);
            float dx = 0f, dy = 0f;
            if (id == 14) dy = 0.12f * lt.fontPx * (1f - e);              // 단어: 아래에서
            else if (id == 15) dx = dir * 0.30f * lt.fontPx * (1f - e);   // 글자: 옆에서
            else if (id == 16) dy = 0.10f * lt.fontPx * (1f - e);         // 가운데서 퍼짐
            else if (id == 17) dy = -0.12f * lt.fontPx * (1f - e);        // 단어: 위에서
            else dy = 0.16f * lt.fontPx * (1f - e);                       // 글자: 아래에서
            float cx = vw * 0.5f + (s[0] + s[2]) * 0.5f - lt.w * 0.5f + dx;
            float cy = vh * 0.5f + (s[1] + s[3]) * 0.5f - lt.h * 0.5f + dy;
            fillQuad(cx, cy, (s[2] - s[0]) * 0.5f, (s[3] - s[1]) * 0.5f, 0f);
            setUv(s[0] / lt.w, s[2] / lt.w, s[1] / lt.h, s[3] / lt.h);
            drawTex(lt.tex, Math.min(1f, e), 0f, 1f, 0f, 1f);
        }
    }

    private void drawExiting(long now) {
        LineTex lt = exiting;
        float el = (now - exitStart) / 1e6f;
        int dur = exitId == 1 ? 240 : 220;
        if (el >= dur) {
            free(lt);
            exiting = null;
            return;
        }
        float p = el / dur;
        pose.reset();
        pose.alpha = 1f - p;
        int dir = song != null ? song.dir : 1;
        if (exitId == 1) pose.dy = -0.15f * lt.fontPx * p;
        else if (exitId == 2) pose.scale = 1f - 0.03f * p;
        else if (exitId == 3) pose.dx = dir * 0.02f * vw * p;
        else if (exitId == 4) pose.scale = 1f + 0.03f * p;
        drawWhole(lt, pose);
    }

    private void drawWhole(LineTex lt, Pose o) {
        fillQuad(vw * 0.5f + o.dx, vh * 0.5f + o.dy, lt.w * 0.5f * o.scale * o.sx, lt.h * 0.5f * o.scale * o.sy, o.rot);
        setUv(0f, 1f, 0f, 1f);
        drawTex(lt.tex, o.alpha, o.u0, o.u1, o.v0, o.v1);
    }

    /** 화면 픽셀 좌표(왼쪽 위 기준)의 사각형을 클립 좌표로 바꿔 qp 에 넣는다. 순서: 왼아래, 오른아래, 왼위, 오른위 */
    private void fillQuad(float cx, float cy, float hw, float hh, float rot) {
        lx[0] = -hw;
        lx[1] = hw;
        lx[2] = -hw;
        lx[3] = hw;
        ly[0] = hh;
        ly[1] = hh;
        ly[2] = -hh;
        ly[3] = -hh;
        float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        for (int i = 0; i < 4; i++) {
            float x = lx[i] * cs - ly[i] * sn + cx;
            float y = lx[i] * sn + ly[i] * cs + cy;
            qp[i * 2] = 2f * x / vw - 1f;
            qp[i * 2 + 1] = 1f - 2f * y / vh;
        }
    }

    private void setUv(float u0, float u1, float v0, float v1) {
        qt[0] = u0;
        qt[1] = v1;
        qt[2] = u1;
        qt[3] = v1;
        qt[4] = u0;
        qt[5] = v0;
        qt[6] = u1;
        qt[7] = v0;
    }

    private void drawTex(int tex, float alpha, float u0, float u1, float v0, float v1) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1f(txtA, alpha);
        GLES20.glUniform4f(txtClip, u0, u1, v0, v1);
        posBuf.clear();
        posBuf.put(qp).position(0);
        uvBuf.clear();
        uvBuf.put(qt).position(0);
        GLES20.glVertexAttribPointer(txt.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glVertexAttribPointer(txtUV, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ------------------------------------------------------------ 텍스처와 미리 만들기
    private void upload(LineTex lt) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        lt.tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lt.tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, lt.bmp, 0);
        lt.bmp.recycle();
        lt.bmp = null;
    }

    private void free(LineTex lt) {
        if (lt == null) return;
        if (lt.tex != 0 && glReady) GLES20.glDeleteTextures(1, new int[]{lt.tex}, 0);
        lt.tex = 0;
        if (lt.bmp != null) {
            lt.bmp.recycle();
            lt.bmp = null;
        }
    }

    private void freeLines() {
        free(cur);
        free(exiting);
        cur = null;
        exiting = null;
    }

    private void clearReady() {
        for (LineTex lt : ready.values()) {
            if (lt.bmp != null) {
                lt.bmp.recycle();
                lt.bmp = null;
            }
        }
        ready.clear();
        pending.clear();
    }

    private LineTex buildFor(Fx.Song s, Lrc.Line ln, int idx, int w, int h) {
        try {
            Random r = new Random();
            int en = s != null ? s.nextEnter(r) : -1;
            int dc = s != null ? s.nextDeco(r, idx) : -1;
            return LineTex.build(ln.text, w, h, en, dc, r);
        } catch (Throwable e) {
            Log.e(TAG, "line build failed", e);
            return null;
        }
    }

    /** 다음 줄을 백그라운드에서 미리 비트맵으로 만들어 둔다 (줄이 바뀌는 순간 끊기지 않게). */
    private void prefetch(final int idx) {
        final Lrc.Line[] L = lines;
        if (idx < 0 || idx >= L.length) return;
        String tx = L[idx].text;
        if (tx == null || tx.length() == 0) return;
        if (ready.containsKey(idx) || !pending.add(idx)) return;
        final int g = gen, w = vw, h = vh;
        final Fx.Song s = song;
        prep.post(new Runnable() {
            @Override
            public void run() {
                LineTex lt = buildFor(s, L[idx], idx, w, h);
                if (lt != null && g == gen) ready.put(idx, lt);
                else if (lt != null && lt.bmp != null) lt.bmp.recycle();
                pending.remove(idx);
            }
        });
    }

    // ------------------------------------------------------------ 셰이더 컴파일
    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] st = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0);
        if (st[0] == 0) {
            Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] st = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0);
        if (st[0] == 0) {
            Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}

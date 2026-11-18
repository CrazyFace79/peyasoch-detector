package com.peyasoch.detector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {

    static final int BG = 0xFF0A0506, PANEL = 0xFF140A0C, LINE = 0xFF2A1418, RED = 0xFFE3223A,
            RED2 = 0xFFFF4D5E, AMB = 0xFFFFB020, GRN = 0xFF2FD27A, TXT = 0xFFF1E6E7, MUT = 0xFF9A8487, BLUE = 0xFF4AA3FF;

    // ---- clases de detección ----
    static final String NONE = "none", SMALL = "small", MEDIUM = "medium", MAGNET = "magnet", STRONG = "strong", AC = "ac", SAT = "sat";
    static final Map<String, String[]> CL = new HashMap<>();
    static {
        CL.put(NONE, new String[]{"✅", "Nada detectado", "Solo el campo magnético normal de la Tierra.", ""});
        CL.put(SMALL, new String[]{"🔩", "Metal pequeño", "Hierro o acero pequeño o algo lejos: tornillo, clavo, grapa, llave o moneda de acero.", "Metal pequeño detectado"});
        CL.put(MEDIUM, new String[]{"🔧", "Objeto metálico", "Hierro o acero de tamaño medio: herramienta, cubierto, tubería, varilla o perfil en la pared.", "Objeto metálico detectado"});
        CL.put(MAGNET, new String[]{"🧲", "Imán detectado", "Imán o acero grande muy cerca: altavoz, auricular, micrófono, cierre magnético, funda con imán o motor.", "Imán detectado"});
        CL.put(STRONG, new String[]{"⚠️", "Imán potente", "Campo muy fuerte: imán de neodimio, altavoz grande o disco duro.", "Imán potente detectado"});
        CL.put(AC, new String[]{"⚡", "Campo eléctrico variable", "Campo que oscila: cable con corriente, cargador, transformador, motor o aparato encendido.", "Campo eléctrico variable. Posible cable con corriente"});
        CL.put(SAT, new String[]{"⛔", "Sensor saturado", "El campo supera el límite del sensor: imán muy potente pegado al móvil. Aléjalo.", "Sensor saturado. Imán muy potente"});
    }

    SensorManager sm;
    Sensor mag;
    TextToSpeech tts;
    boolean ttsOk = false;
    ToneGenerator tone;
    Vibrator vibrator;
    final Handler h = new Handler(Looper.getMainLooper());

    // estado
    boolean running = false;
    final ArrayDeque<double[]> buf = new ArrayDeque<>(); // {t ms, m}
    final List<Double> calib = new ArrayList<>();
    double base = Double.NaN;
    long calibUntil = 0;
    String cls = NONE, cand = null;
    long candT = 0, lastBeep = 0;
    final Map<String, Long> lastSay = new HashMap<>();
    String evtCls = null;
    long evtT0 = 0;
    double evtPeak = 0;
    Date evtDate;
    final List<String[]> log = new ArrayList<>();
    double lastFast = 0;
    boolean unreliable = false;

    // vistas
    LinearLayout verdict;
    TextView vIcon, vTitle, vDesc, vConf, nB, nD, nA, stTxt, logView, sensV, warn;
    View barFill;
    LinearLayout barBox;
    GraphView graph;
    Button bStart;
    SeekBar sens;
    CheckBox oVoz, oBeep, oVib;

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(16));
        g.setStroke((int) dp(1), stroke);
        return g;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(14);
        l.setPadding(p, p, p, p);
        l.setBackground(box(PANEL, LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button btn(String s, int fill) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = box(fill, fill == RED ? RED : LINE);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        return b;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mag = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (Exception e) { tone = null; }
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "ES"));
                ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        buildUi();
        if (mag == null) {
            showWarn("Este móvil no tiene magnetómetro. No puede detectar metales ni imanes.");
            bStart.setEnabled(false);
        }
    }

    void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(14);
        root.setPadding(p, (int) dp(18), p, (int) dp(40));
        sv.addView(root);

        // cabecera
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView b1 = tv("PEYASOCH · DETECTOR", 13, RED2, true);
        b1.setLetterSpacing(0.18f);
        brand.addView(b1);
        brand.addView(tv("Detector magnético de objetos", 11, MUT, false));
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        stTxt = tv("● Parado", 12, MUT, false);
        head.addView(stTxt);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.bottomMargin = (int) dp(12);
        root.addView(head, hl);

        // veredicto
        verdict = card();
        verdict.setGravity(Gravity.CENTER_HORIZONTAL);
        vIcon = tv("🧲", 44, TXT, false);
        vTitle = tv("Pulsa INICIAR", 22, TXT, true);
        vDesc = tv("Aleja el móvil de metales al empezar: los 2 primeros segundos se calibra con el campo de la Tierra.", 14, MUT, false);
        vConf = tv("", 12, MUT, false);
        for (TextView t : new TextView[]{vIcon, vTitle, vDesc, vConf}) {
            t.setGravity(Gravity.CENTER);
            verdict.addView(t);
        }
        vTitle.setPadding(0, (int) dp(8), 0, 0);
        vDesc.setPadding(0, (int) dp(6), 0, 0);
        vConf.setPadding(0, (int) dp(10), 0, 0);
        root.addView(verdict);

        warn = tv("", 14, AMB, false);
        warn.setBackground(box(0xFF1E1508, AMB));
        warn.setPadding(p, p, p, p);
        warn.setVisibility(View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.bottomMargin = (int) dp(12);
        root.addView(warn, wl);

        // números
        LinearLayout nc = card();
        LinearLayout nums = new LinearLayout(this);
        nB = tv("--", 24, TXT, true);
        nD = tv("--", 24, TXT, true);
        nA = tv("--", 24, TXT, true);
        String[] lab = {"CAMPO µT", "DIFERENCIA", "OSCILACIÓN"};
        TextView[] vals = {nB, nD, nA};
        for (int i = 0; i < 3; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(vals[i]);
            c.addView(tv(lab[i], 10, MUT, false));
            nums.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        }
        nc.addView(nums);
        barBox = new LinearLayout(this);
        GradientDrawable bb = box(0xFF1F1013, 0xFF1F1013);
        bb.setCornerRadius(dp(7));
        barBox.setBackground(bb);
        barFill = new View(this);
        GradientDrawable bf = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{GRN, AMB, RED});
        bf.setCornerRadius(dp(7));
        barFill.setBackground(bf);
        barBox.addView(barFill, new LinearLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, (int) dp(14));
        bl.topMargin = (int) dp(12);
        nc.addView(barBox, bl);
        root.addView(nc);

        // gráfica
        LinearLayout gc = card();
        gc.addView(tv("ÚLTIMOS 30 s · DIFERENCIA µT", 11, MUT, true));
        graph = new GraphView(this);
        gc.addView(graph, new LinearLayout.LayoutParams(-1, (int) dp(130)));
        root.addView(gc);

        // controles
        LinearLayout cc = card();
        bStart = btn("INICIAR", RED);
        bStart.setTextSize(16);
        cc.addView(bStart, new LinearLayout.LayoutParams(-1, (int) dp(56)));
        LinearLayout row = new LinearLayout(this);
        Button bCal = btn("Recalibrar", 0xFF1D0E11);
        Button bClr = btn("Borrar registro", 0xFF1D0E11);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, (int) dp(48), 1);
        half.setMargins(0, (int) dp(8), (int) dp(4), 0);
        row.addView(bCal, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, (int) dp(48), 1);
        half2.setMargins((int) dp(4), (int) dp(8), 0, 0);
        row.addView(bClr, half2);
        cc.addView(row);

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, (int) dp(10), 0, 0);
        sr.addView(tv("Sensibilidad", 14, TXT, false));
        sens = new SeekBar(this);
        sens.setMax(20);
        sens.setProgress(5); // 0.5 + p*0.1 → 1.0
        sr.addView(sens, new LinearLayout.LayoutParams(0, -2, 1));
        sensV = tv("1.0×", 14, TXT, true);
        sr.addView(sensV);
        cc.addView(sr);

        LinearLayout tg = new LinearLayout(this);
        oVoz = chk("Voz");
        oBeep = chk("Pitido");
        oVib = chk("Vibración");
        tg.addView(oVoz);
        tg.addView(oBeep);
        tg.addView(oVib);
        cc.addView(tg);
        root.addView(cc);

        // registro
        LinearLayout lc = card();
        lc.addView(tv("REGISTRO DE DETECCIONES", 11, MUT, true));
        logView = tv("Sin detecciones todavía", 13, TXT, false);
        logView.setPadding(0, (int) dp(8), 0, (int) dp(8));
        logView.setLineSpacing(dp(4), 1f);
        lc.addView(logView);
        Button bShare = btn("Compartir registro", 0xFF1D0E11);
        lc.addView(bShare, new LinearLayout.LayoutParams(-1, (int) dp(48)));
        root.addView(lc);

        // ayuda
        LinearLayout hc = card();
        hc.addView(tv("CÓMO USARLO", 11, MUT, true));
        TextView help = tv("El sensor magnético suele estar arriba, junto a la cámara. Pasa esa zona despacio y paralela a la superficie, sin girar el móvil.\n\n"
                + "Detecta: hierro y acero (tornillos, clavos, llaves, herramientas, tuberías, varillas en pared), imanes (altavoces, auriculares, cierres magnéticos) y campos que oscilan (cables con corriente, cargadores, transformadores, motores).\n\n"
                + "No detecta: aluminio, cobre, oro ni plata, porque no reaccionan a un campo magnético estático.", 13, MUT, false);
        help.setPadding(0, (int) dp(8), 0, 0);
        help.setLineSpacing(dp(2), 1f);
        hc.addView(help);
        root.addView(hc);

        TextView foot = tv("PEYASOCH", 11, 0xFF5E4A4D, false);
        foot.setGravity(Gravity.CENTER);
        foot.setLetterSpacing(0.2f);
        root.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        setContentView(sv);

        bStart.setOnClickListener(v -> { if (running) stop(); else start(); });
        bCal.setOnClickListener(v -> { if (running) calibrate(); });
        bClr.setOnClickListener(v -> { log.clear(); renderLog(); });
        bShare.setOnClickListener(v -> shareLog());
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { sensV.setText(String.format(Locale.US, "%.1f×", k())); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    CheckBox chk(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TXT);
        c.setChecked(true);
        c.setPadding(0, 0, (int) dp(10), 0);
        return c;
    }

    double k() { return 0.5 + sens.getProgress() * 0.1; }

    void showWarn(String s) {
        warn.setText(s);
        warn.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void setStatus(boolean on, String t) {
        stTxt.setText("● " + t);
        stTxt.setTextColor(on ? GRN : MUT);
    }

    // ---- control ----
    void start() {
        if (mag == null) return;
        buf.clear();
        int rate = SensorManager.SENSOR_DELAY_FASTEST;
        boolean ok = sm.registerListener(this, mag, rate);
        if (!ok) { showWarn("No se pudo abrir el sensor magnético. Cierra otras apps que lo usen y vuelve a intentarlo."); return; }
        showWarn("");
        running = true;
        bStart.setText("PARAR");
        calibrate();
        h.post(tick);
    }

    void stop() {
        running = false;
        sm.unregisterListener(this);
        h.removeCallbacks(tick);
        if (evtCls != null) { addLog(SystemClock.elapsedRealtime()); evtCls = null; }
        bStart.setText("INICIAR");
        setStatus(false, "Parado");
    }

    void calibrate() {
        base = Double.NaN;
        calib.clear();
        calibUntil = SystemClock.elapsedRealtime() + 2000;
        cls = "__";
        cand = null;
        evtCls = null;
        setStatus(true, "Calibrando");
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = SystemClock.elapsedRealtime();
        double x = e.values[0], y = e.values[1], z = e.values[2];
        double m = Math.sqrt(x * x + y * y + z * z);
        if (Double.isNaN(m) || Double.isInfinite(m)) return;
        buf.addLast(new double[]{now, m});
        while (!buf.isEmpty() && now - buf.peekFirst()[0] > 30000) buf.pollFirst();
        if (now < calibUntil) { calib.add(m); return; }
        if (!calib.isEmpty()) {
            Double[] a = calib.toArray(new Double[0]);
            Arrays.sort(a);
            base = a[a.length / 2];
            calib.clear();
            speak("Calibrado. Detector listo");
            setStatus(true, "Detectando");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int acc) {
        unreliable = acc == SensorManager.SENSOR_STATUS_UNRELIABLE;
        if (running) showWarn(unreliable ? "El sensor está descalibrado. Mueve el móvil dibujando un 8 en el aire unos segundos y pulsa Recalibrar." : "");
    }

    // ---- análisis ----
    static double mean(List<Double> a) { double s = 0; for (double v : a) s += v; return a.isEmpty() ? 0 : s / a.size(); }
    static double std(List<Double> a) { double m = mean(a), s = 0; for (double v : a) s += (v - m) * (v - m); return a.isEmpty() ? 0 : Math.sqrt(s / a.size()); }

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (now < calibUntil) {
                vIcon.setText("⏳"); vTitle.setText("Calibrando…"); vDesc.setText("Mantén el móvil quieto y lejos de metales.");
                verdict.setBackground(box(PANEL, LINE));
            } else if (!Double.isNaN(base)) {
                analyse(now);
                graph.update(buf, base, now, 5 / k());
            }
            h.postDelayed(this, 50);
        }
    };

    void analyse(long now) {
        List<double[]> win = new ArrayList<>();
        for (Iterator<double[]> it = buf.descendingIterator(); it.hasNext(); ) {
            double[] s = it.next();
            if (now - s[0] > 1000) break;
            win.add(s);
        }
        if (win.size() < 8) return;
        double m = win.get(0)[1];
        List<Double> recent = new ArrayList<>();
        for (double[] s : win) if (now - s[0] <= 120) recent.add(s[1]);
        if (recent.isEmpty()) recent.add(m);
        double mSm = mean(recent);
        // oscilación: residuo dentro de bloques de 200 ms frente a la variación entre bloques
        List<Double> res = new ArrayList<>(), bm = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            List<Double> b = new ArrayList<>();
            for (double[] s : win) { double d = now - s[0]; if (d > i * 200 && d <= (i + 1) * 200) b.add(s[1]); }
            if (b.isEmpty()) continue;
            double mm = mean(b);
            bm.add(mm);
            for (double v : b) res.add(v - mm);
        }
        double fast = std(res), slow = std(bm);
        List<Double> last = new ArrayList<>();
        for (int i = 0; i < Math.min(10, win.size()); i++) last.add(win.get(i)[1]);
        boolean stuck = std(last) < 0.001 && m > 300;

        double kk = k();
        double diff = mSm - base, d = Math.abs(diff);
        String c;
        int conf;
        double acThr = 0.9 / kk, t1 = 5 / kk, t2 = 18 / kk, t3 = 70 / kk, t4 = 350 / kk;
        if (m > 1800 || stuck) { c = SAT; conf = 99; }
        else if (fast > acThr && fast > slow * 0.7 && d < 300 / kk) { c = AC; conf = (int) Math.min(99, 50 + fast / acThr * 15); }
        else {
            double lo = 0, hi = t1;
            if (d >= t4) { c = STRONG; lo = t4; hi = t4 * 3; }
            else if (d >= t3) { c = MAGNET; lo = t3; hi = t4; }
            else if (d >= t2) { c = MEDIUM; lo = t2; hi = t3; }
            else if (d >= t1) { c = SMALL; lo = t1; hi = t2; }
            else c = NONE;
            conf = c.equals(NONE) ? (int) Math.round(100 - Math.min(60, d / t1 * 60)) : (int) Math.round(55 + Math.min(44, (d - lo) / (hi - lo) * 44));
        }

        nB.setText(String.format(Locale.US, "%.1f", m));
        nD.setText(String.format(Locale.US, "%+.1f", diff));
        nA.setText(String.format(Locale.US, "%.2f", fast));
        double lvl = Math.min(1, Math.log10(1 + d * kk) / Math.log10(401));
        if (c.equals(AC)) lvl = Math.max(lvl, Math.min(1, fast / acThr / 4));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) barFill.getLayoutParams();
        lp.width = (int) (barBox.getWidth() * lvl);
        barFill.setLayoutParams(lp);
        beep(c.equals(NONE) ? 0 : lvl, now);

        if (!c.equals(cls)) {
            if (!c.equals(cand)) { cand = c; candT = now; }
            else if (now - candT >= 300) { commit(c, d, now); cand = null; }
        } else {
            cand = null;
            if (evtCls != null) evtPeak = Math.max(evtPeak, d);
        }
        vConf.setText(cls.equals(NONE) || cls.equals("__") ? "" :
                String.format(Locale.US, "Fiabilidad estimada: %d%% · pico %.1f µT", conf, evtCls != null ? evtPeak : d));
    }

    void commit(String c, double d, long now) {
        if (evtCls != null && (c.equals(NONE) || !c.equals(evtCls))) {
            if (now - evtT0 >= 250) addLog(now);
            evtCls = null;
        }
        if (!c.equals(NONE) && evtCls == null) { evtCls = c; evtT0 = now; evtPeak = d; evtDate = new Date(); }
        cls = c;
        String[] C = CL.get(c);
        vIcon.setText(C[0]); vTitle.setText(C[1]); vDesc.setText(C[2]);
        int fill = PANEL, stroke = LINE;
        switch (c) {
            case SMALL: fill = 0xFF1E1508; stroke = AMB; break;
            case MEDIUM: fill = 0xFF22080C; stroke = RED; break;
            case MAGNET: case STRONG: case SAT: fill = 0xFF33070D; stroke = RED2; break;
            case AC: fill = 0xFF08142A; stroke = BLUE; break;
        }
        verdict.setBackground(box(fill, stroke));
        if (!c.equals(NONE)) {
            Long ls = lastSay.get(c);
            if (ls == null || now - ls > 2500) { speak(C[3]); lastSay.put(c, now); }
            long[] pat;
            switch (c) {
                case SMALL: pat = new long[]{0, 60}; break;
                case MEDIUM: pat = new long[]{0, 80, 40, 80}; break;
                case AC: pat = new long[]{0, 30, 30, 30, 30, 30}; break;
                default: pat = new long[]{0, 200, 60, 200};
            }
            vibrate(pat);
        }
    }

    // ---- salidas ----
    void speak(String t) {
        if (!oVoz.isChecked() || !ttsOk || t == null || t.isEmpty()) return;
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "trz");
    }

    void vibrate(long[] p) {
        if (!oVib.isChecked() || vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(p, -1));
        else vibrator.vibrate(p, -1);
    }

    void beep(double lvl, long now) {
        if (!oBeep.isChecked() || tone == null || lvl <= 0) return;
        long interval = (long) (600 - lvl * 520); // más cerca → más rápido
        if (now - lastBeep >= interval) {
            lastBeep = now;
            int t = lvl > 0.75 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP;
            tone.startTone(t, 40);
        }
    }

    void addLog(long now) {
        String[] C = CL.get(evtCls);
        String hora = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(evtDate);
        log.add(0, new String[]{hora, C[1], String.format(Locale.US, "%.1f", evtPeak), String.format(Locale.US, "%.1f", (now - evtT0) / 1000.0)});
        if (log.size() > 200) log.remove(log.size() - 1);
        renderLog();
    }

    void renderLog() {
        if (log.isEmpty()) { logView.setText("Sin detecciones todavía"); return; }
        StringBuilder sb = new StringBuilder();
        for (String[] r : log) sb.append(r[0]).append("  ").append(r[1]).append(" · ").append(r[2]).append(" µT · ").append(r[3]).append(" s\n");
        logView.setText(sb.toString().trim());
    }

    void shareLog() {
        StringBuilder sb = new StringBuilder("PEYASOCH Detector · registro\nHora;Tipo;Pico µT;Duración s\n");
        for (String[] r : log) sb.append(r[0]).append(';').append(r[1]).append(';').append(r[2]).append(';').append(r[3]).append('\n');
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Compartir registro"));
    }

    @Override protected void onPause() { super.onPause(); if (running) stop(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tts != null) tts.shutdown();
        if (tone != null) tone.release();
    }
}

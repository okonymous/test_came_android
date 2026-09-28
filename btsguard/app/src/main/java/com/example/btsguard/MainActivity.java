package com.example.btsguard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 42;

    private TelephonyManager telephony;
    private LocationManager locationManager;
    private WebView map;
    private TextView riskView;
    private TextView summaryView;
    private TextView cellsView;
    private EditText bearingInput;

    private final List<SignalSample> signalSamples = new ArrayList<>();
    private final List<BearingSample> bearingSamples = new ArrayList<>();

    private String previousRat = null;
    private Integer previousTac = null;
    private Location previousLocation = null;
    private long previousScanMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setContentView(buildUi());
        requestNeededPermissions();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        TextView title = new TextView(this);
        title.setText("BTS Guard DF");
        title.setTextSize(25f);
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Passive cellular anomaly detection + direction finding assistant");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(8));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button scanBtn = new Button(this);
        scanBtn.setText("SCAN BTS");
        scanBtn.setOnClickListener(v -> scanCells());
        actions.addView(scanBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button clearBtn = new Button(this);
        clearBtn.setText("CLEAR");
        clearBtn.setOnClickListener(v -> clearSession());
        actions.addView(clearBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        riskView = new TextView(this);
        riskView.setText("Risk: belum ada data");
        riskView.setTextSize(18f);
        riskView.setTextColor(Color.rgb(55, 65, 81));
        riskView.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(riskView, new LinearLayout.LayoutParams(-1, -2));

        summaryView = new TextView(this);
        summaryView.setText("Tekan SCAN BTS untuk membaca serving/neighboring cells yang tersedia dari modem Android.");
        summaryView.setTextSize(13f);
        summaryView.setTextColor(Color.rgb(31, 41, 55));
        summaryView.setPadding(dp(4), 0, dp(4), dp(6));
        root.addView(summaryView, new LinearLayout.LayoutParams(-1, -2));

        map = new WebView(this);
        WebSettings ws = map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        map.setBackgroundColor(Color.rgb(11, 18, 32));
        map.loadUrl("file:///android_asset/map.html");
        root.addView(map, new LinearLayout.LayoutParams(-1, dp(300)));

        LinearLayout dfRow = new LinearLayout(this);
        dfRow.setOrientation(LinearLayout.HORIZONTAL);
        dfRow.setPadding(0, dp(6), 0, 0);

        bearingInput = new EditText(this);
        bearingInput.setHint("Bearing 0-359°");
        bearingInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        dfRow.addView(bearingInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button bearingBtn = new Button(this);
        bearingBtn.setText("ADD DF");
        bearingBtn.setOnClickListener(v -> addBearing());
        dfRow.addView(bearingBtn, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(dfRow, new LinearLayout.LayoutParams(-1, -2));

        TextView dfHint = new TextView(this);
        dfHint.setText("DF eksternal: masukkan azimuth dari perangkat RF/SDR/DF pada minimal 2 posisi berbeda untuk triangulasi.");
        dfHint.setTextSize(11f);
        dfHint.setTextColor(Color.DKGRAY);
        root.addView(dfHint, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        cellsView = new TextView(this);
        cellsView.setText("Cell details akan tampil di sini.");
        cellsView.setTextSize(12f);
        cellsView.setTextColor(Color.rgb(17, 24, 39));
        cellsView.setPadding(dp(4), dp(8), dp(4), dp(20));
        scroll.addView(cellsView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        return root;
    }

    private void requestNeededPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (!ok) Toast.makeText(this, "Precise location diperlukan untuk membaca CellInfo pada banyak perangkat Android.", Toast.LENGTH_LONG).show();
        }
    }

    private void scanCells() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }

        Location loc = getBestLastLocation();
        if (loc != null) js(String.format(Locale.US, "setCurrent(%f,%f)", loc.getLatitude(), loc.getLongitude()));

        List<CellInfo> infos;
        try {
            infos = telephony.getAllCellInfo();
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission telephony/location belum tersedia.", Toast.LENGTH_LONG).show();
            return;
        }

        if (infos == null || infos.isEmpty()) {
            riskView.setText("Risk: data CellInfo tidak tersedia");
            summaryView.setText("Modem/ROM tidak mengembalikan cell list saat ini. Aktifkan lokasi, SIM, dan mobile network lalu coba lagi.");
            return;
        }

        String simPlmn = "";
        try { simPlmn = telephony.getSimOperator(); } catch (Exception ignored) {}

        ArrayList<CellRecord> records = new ArrayList<>();
        for (CellInfo ci : infos) {
            CellRecord r = parseCell(ci);
            if (r != null) records.add(r);
        }
        if (records.isEmpty()) return;

        Collections.sort(records, Comparator.comparing((CellRecord r) -> !r.registered).thenComparingInt(r -> -r.dbm));
        CellRecord serving = null;
        int neighbors = 0;
        for (CellRecord r : records) {
            if (r.registered && serving == null) serving = r;
            else neighbors++;
        }
        if (serving == null) serving = records.get(0);

        RiskResult risk = scoreRisk(serving, neighbors, simPlmn, loc);
        renderRisk(risk);

        StringBuilder detail = new StringBuilder();
        detail.append("SIM PLMN: ").append(simPlmn.isEmpty() ? "unknown" : simPlmn).append('\n');
        detail.append("Serving/neighbor cells: ").append(records.size()).append("\n\n");
        for (CellRecord r : records) {
            detail.append(r.registered ? "[SERVING] " : "[NEIGHBOR] ")
                    .append(r.rat).append("  ")
                    .append(r.mcc).append('-').append(r.mnc)
                    .append("  ").append(r.dbm).append(" dBm\n")
                    .append("  ").append(r.extra).append("\n\n");
        }
        cellsView.setText(detail.toString());

        if (loc != null) {
            signalSamples.add(new SignalSample(loc.getLatitude(), loc.getLongitude(), serving.dbm));
            js(String.format(Locale.US, "addCellSample(%f,%f,%d,'%s')",
                    loc.getLatitude(), loc.getLongitude(), serving.dbm, jsEscape(serving.rat + " " + serving.mcc + "-" + serving.mnc)));
            updateSignalGradientEstimate();
        }

        previousRat = serving.rat;
        previousTac = serving.tac;
        previousLocation = loc;
        previousScanMs = System.currentTimeMillis();
    }

    private CellRecord parseCell(CellInfo ci) {
        try {
            if (ci instanceof CellInfoNr) {
                CellInfoNr n = (CellInfoNr) ci;
                CellIdentityNr id = (CellIdentityNr) n.getCellIdentity();
                CellSignalStrengthNr ss = (CellSignalStrengthNr) n.getCellSignalStrength();
                String extra = "NCI=" + id.getNci() + " TAC=" + id.getTac() + " PCI=" + id.getPci() +
                        " NRARFCN=" + id.getNrarfcn() + " SS-RSRP=" + ss.getSsRsrp() + " SS-RSRQ=" + ss.getSsRsrq();
                return new CellRecord("5G NR", safe(id.getMccString()), safe(id.getMncString()), id.getTac(), ss.getDbm(), ci.isRegistered(), extra);
            }
            if (ci instanceof CellInfoLte) {
                CellInfoLte l = (CellInfoLte) ci;
                CellIdentityLte id = l.getCellIdentity();
                CellSignalStrengthLte ss = l.getCellSignalStrength();
                String extra = "CI=" + id.getCi() + " TAC=" + id.getTac() + " PCI=" + id.getPci() +
                        " EARFCN=" + id.getEarfcn() + " RSRP=" + ss.getRsrp() + " RSRQ=" + ss.getRsrq();
                return new CellRecord("4G LTE", safe(id.getMccString()), safe(id.getMncString()), id.getTac(), ss.getDbm(), ci.isRegistered(), extra);
            }
            if (ci instanceof CellInfoWcdma) {
                CellInfoWcdma w = (CellInfoWcdma) ci;
                CellIdentityWcdma id = w.getCellIdentity();
                CellSignalStrengthWcdma ss = w.getCellSignalStrength();
                String extra = "CID=" + id.getCid() + " LAC=" + id.getLac() + " PSC=" + id.getPsc() + " UARFCN=" + id.getUarfcn();
                return new CellRecord("3G WCDMA", safe(id.getMccString()), safe(id.getMncString()), id.getLac(), ss.getDbm(), ci.isRegistered(), extra);
            }
            if (ci instanceof CellInfoGsm) {
                CellInfoGsm g = (CellInfoGsm) ci;
                CellIdentityGsm id = g.getCellIdentity();
                CellSignalStrengthGsm ss = g.getCellSignalStrength();
                String extra = "CID=" + id.getCid() + " LAC=" + id.getLac() + " ARFCN=" + id.getArfcn() + " BSIC=" + id.getBsic();
                return new CellRecord("2G GSM", safe(id.getMccString()), safe(id.getMncString()), id.getLac(), ss.getDbm(), ci.isRegistered(), extra);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private RiskResult scoreRisk(CellRecord s, int neighbors, String simPlmn, Location loc) {
        int score = 0;
        ArrayList<String> reasons = new ArrayList<>();
        String cellPlmn = s.mcc + s.mnc;

        if (!simPlmn.isEmpty() && !s.mcc.equals("?") && !s.mnc.equals("?") && !cellPlmn.equals(simPlmn)) {
            score += 35;
            reasons.add("Serving PLMN berbeda dari SIM/operator");
        }
        if (s.dbm > -55) {
            score += 20;
            reasons.add("Sinyal serving sangat kuat (> -55 dBm)");
        }
        if (neighbors <= 1) {
            score += 10;
            reasons.add("Neighbor cell sangat sedikit");
        }
        long now = System.currentTimeMillis();
        if (previousRat != null && now - previousScanMs < 120000 && isHighRat(previousRat) && !isHighRat(s.rat)) {
            score += 25;
            reasons.add("Terjadi downgrade cepat dari 4G/5G ke 2G/3G");
        }
        if (previousTac != null && s.tac != null && !previousTac.equals(s.tac) && loc != null && previousLocation != null) {
            float d = loc.distanceTo(previousLocation);
            if (d < 300f) {
                score += 20;
                reasons.add("TAC/LAC berubah pada perpindahan < 300 m");
            }
        }
        if (score > 100) score = 100;
        if (reasons.isEmpty()) reasons.add("Belum ada indikator anomali kuat dari data pasif yang tersedia");
        return new RiskResult(score, reasons);
    }

    private boolean isHighRat(String rat) {
        return rat.contains("LTE") || rat.contains("NR");
    }

    private void renderRisk(RiskResult r) {
        String level;
        int color;
        if (r.score >= 70) { level = "HIGH"; color = Color.rgb(185, 28, 28); }
        else if (r.score >= 40) { level = "MEDIUM"; color = Color.rgb(194, 65, 12); }
        else { level = "LOW"; color = Color.rgb(21, 128, 61); }
        riskView.setText("Suspected Rogue BTS Risk: " + r.score + "/100 — " + level);
        riskView.setTextColor(color);
        StringBuilder sb = new StringBuilder();
        for (String x : r.reasons) sb.append("• ").append(x).append('\n');
        sb.append("\nCatatan: skor ini indikator anomali, bukan bukti forensik bahwa BTS pasti palsu.");
        summaryView.setText(sb.toString());
    }

    private Location getBestLastLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
        } catch (SecurityException ignored) {}
        return best;
    }

    private void updateSignalGradientEstimate() {
        if (signalSamples.size() < 2) return;
        ArrayList<SignalSample> sorted = new ArrayList<>(signalSamples);
        sorted.sort((a, b) -> Integer.compare(b.dbm, a.dbm));
        int n = Math.min(5, sorted.size());
        double lat = 0, lng = 0, weightSum = 0;
        for (int i = 0; i < n; i++) {
            SignalSample s = sorted.get(i);
            double w = Math.max(1, 140 + s.dbm);
            lat += s.lat * w;
            lng += s.lng * w;
            weightSum += w;
        }
        lat /= weightSum;
        lng /= weightSum;
        int radius = Math.max(80, 500 - Math.min(400, signalSamples.size() * 35));
        js(String.format(Locale.US, "showEstimate(%f,%f,%d)", lat, lng, radius));
    }

    private void addBearing() {
        String t = bearingInput.getText().toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, "Masukkan bearing/azimuth.", Toast.LENGTH_SHORT).show();
            return;
        }
        double bearing;
        try { bearing = Double.parseDouble(t); }
        catch (NumberFormatException e) { Toast.makeText(this, "Bearing tidak valid.", Toast.LENGTH_SHORT).show(); return; }
        if (bearing < 0 || bearing >= 360) {
            Toast.makeText(this, "Gunakan nilai 0 sampai <360 derajat.", Toast.LENGTH_SHORT).show();
            return;
        }
        Location loc = getBestLastLocation();
        if (loc == null) {
            Toast.makeText(this, "Lokasi belum tersedia. Aktifkan GPS lalu coba lagi.", Toast.LENGTH_LONG).show();
            return;
        }
        BearingSample b = new BearingSample(loc.getLatitude(), loc.getLongitude(), bearing);
        bearingSamples.add(b);
        double[] end = destinationPoint(b.lat, b.lng, bearing, 5000.0);
        js(String.format(Locale.US, "addBearing(%f,%f,%f,%f,%.1f)", b.lat, b.lng, end[0], end[1], bearing));
        if (bearingSamples.size() >= 2) {
            BearingSample a = bearingSamples.get(bearingSamples.size() - 2);
            BearingSample c = bearingSamples.get(bearingSamples.size() - 1);
            double[] p = intersectBearingsApprox(a, c);
            if (p != null) {
                js(String.format(Locale.US, "showDfTarget(%f,%f)", p[0], p[1]));
                Toast.makeText(this, String.format(Locale.US, "Estimasi intersection: %.5f, %.5f", p[0], p[1]), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Dua bearing hampir paralel / intersection tidak stabil.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Bearing pertama tersimpan. Ambil bearing kedua dari posisi berbeda.", Toast.LENGTH_LONG).show();
        }
        bearingInput.setText("");
    }

    private double[] destinationPoint(double lat, double lon, double bearingDeg, double distanceM) {
        double R = 6371000.0;
        double brng = Math.toRadians(bearingDeg);
        double p1 = Math.toRadians(lat);
        double l1 = Math.toRadians(lon);
        double ad = distanceM / R;
        double p2 = Math.asin(Math.sin(p1) * Math.cos(ad) + Math.cos(p1) * Math.sin(ad) * Math.cos(brng));
        double l2 = l1 + Math.atan2(Math.sin(brng) * Math.sin(ad) * Math.cos(p1), Math.cos(ad) - Math.sin(p1) * Math.sin(p2));
        return new double[]{Math.toDegrees(p2), Math.toDegrees(l2)};
    }

    private double[] intersectBearingsApprox(BearingSample a, BearingSample b) {
        double lat0 = Math.toRadians((a.lat + b.lat) / 2.0);
        double mPerDegLat = 111320.0;
        double mPerDegLon = 111320.0 * Math.cos(lat0);
        double ax = a.lng * mPerDegLon, ay = a.lat * mPerDegLat;
        double bx = b.lng * mPerDegLon, by = b.lat * mPerDegLat;
        double ar = Math.toRadians(a.bearing), br = Math.toRadians(b.bearing);
        double adx = Math.sin(ar), ady = Math.cos(ar);
        double bdx = Math.sin(br), bdy = Math.cos(br);
        double det = adx * (-bdy) - ady * (-bdx);
        if (Math.abs(det) < 0.05) return null;
        double rx = bx - ax, ry = by - ay;
        double ta = (rx * (-bdy) - ry * (-bdx)) / det;
        double tb = (adx * ry - ady * rx) / det;
        if (ta < 0 || tb < 0 || ta > 20000 || tb > 20000) return null;
        double x = ax + ta * adx;
        double y = ay + ta * ady;
        return new double[]{y / mPerDegLat, x / mPerDegLon};
    }

    private void clearSession() {
        signalSamples.clear();
        bearingSamples.clear();
        previousRat = null;
        previousTac = null;
        previousLocation = null;
        previousScanMs = 0;
        riskView.setText("Risk: belum ada data");
        riskView.setTextColor(Color.DKGRAY);
        summaryView.setText("Session dibersihkan.");
        cellsView.setText("Cell details akan tampil di sini.");
        js("clearMap()");
    }

    private void js(String code) {
        map.post(() -> map.evaluateJavascript("javascript:" + code, null));
    }

    private String jsEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    private static String safe(String s) { return s == null || s.isEmpty() ? "?" : s; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    static class CellRecord {
        final String rat, mcc, mnc, extra;
        final Integer tac;
        final int dbm;
        final boolean registered;
        CellRecord(String rat, String mcc, String mnc, Integer tac, int dbm, boolean registered, String extra) {
            this.rat = rat; this.mcc = mcc; this.mnc = mnc; this.tac = tac; this.dbm = dbm; this.registered = registered; this.extra = extra;
        }
    }

    static class RiskResult {
        final int score; final List<String> reasons;
        RiskResult(int score, List<String> reasons) { this.score = score; this.reasons = reasons; }
    }

    static class SignalSample {
        final double lat, lng; final int dbm;
        SignalSample(double lat, double lng, int dbm) { this.lat = lat; this.lng = lng; this.dbm = dbm; }
    }

    static class BearingSample {
        final double lat, lng, bearing;
        BearingSample(double lat, double lng, double bearing) { this.lat = lat; this.lng = lng; this.bearing = bearing; }
    }
}

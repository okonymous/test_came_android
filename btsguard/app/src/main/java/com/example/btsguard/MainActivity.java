package com.example.btsguard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 42;
    private static final int C_BG = Color.rgb(7,13,25);
    private static final int C_CARD = Color.rgb(15,23,42);
    private static final int C_CARD2 = Color.rgb(20,30,52);
    private static final int C_TEXT = Color.rgb(241,245,249);
    private static final int C_MUTED = Color.rgb(148,163,184);
    private static final int C_BLUE = Color.rgb(59,130,246);
    private static final int C_CYAN = Color.rgb(34,211,238);
    private static final int C_GREEN = Color.rgb(34,197,94);
    private static final int C_AMBER = Color.rgb(245,158,11);
    private static final int C_RED = Color.rgb(239,68,68);

    private TelephonyManager telephony;
    private LocationManager locationManager;

    private WebView map;
    private boolean mapReady = false;
    private final List<String> pendingJs = new ArrayList<>();

    private FrameLayout contentHost;
    private LinearLayout nearbyPanel, scannerPanel, dfPanel;
    private Button tabNearby, tabScanner, tabDf;

    private TextView globalStatus, nearbyStatus, nearbyList;
    private EditText radiusInput;
    private TextView riskView, summaryView, cellsView;
    private EditText bearingInput;
    private TextView dfStatus;

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
        showTab("nearby");
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(12),dp(12),dp(10));
        root.setBackgroundColor(C_BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("BTS Guard DF",25,C_TEXT,true));
        titles.addView(text("Open telecom map • Cell scanner • DF",11,C_MUTED,false));
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1f));
        globalStatus = text("● READY",11,C_GREEN,true);
        globalStatus.setPadding(dp(10),dp(7),dp(10),dp(7));
        globalStatus.setBackground(round(C_CARD2,16,C_GREEN,1));
        header.addView(globalStatus);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2); hp.bottomMargin=dp(8);
        root.addView(header,hp);

        LinearLayout tabs=new LinearLayout(this);
        tabNearby=tabButton("NEARBY BTS");
        tabScanner=tabButton("SCANNER");
        tabDf=tabButton("DF");
        tabNearby.setOnClickListener(v->showTab("nearby"));
        tabScanner.setOnClickListener(v->showTab("scanner"));
        tabDf.setOnClickListener(v->showTab("df"));
        tabs.addView(tabNearby,new LinearLayout.LayoutParams(0,dp(42),1f));
        tabs.addView(tabScanner,new LinearLayout.LayoutParams(0,dp(42),1f));
        tabs.addView(tabDf,new LinearLayout.LayoutParams(0,dp(42),1f));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2); tp.bottomMargin=dp(8);
        root.addView(tabs,tp);

        LinearLayout mapCard=card();
        TextView mt=text("LIVE MAP",11,C_MUTED,true);
        mt.setPadding(dp(12),dp(9),dp(12),dp(6));
        mapCard.addView(mt);

        map=new WebView(this);
        map.setBackgroundColor(Color.rgb(8,17,31));
        WebSettings ws=map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        map.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                mapReady=true;
                for(String code:pendingJs) map.evaluateJavascript("javascript:"+code,null);
                pendingJs.clear();
            }
        });
        map.loadUrl("file:///android_asset/map.html");
        mapCard.addView(map,new LinearLayout.LayoutParams(-1,dp(300)));
        root.addView(mapCard);

        contentHost=new FrameLayout(this);
        LinearLayout.LayoutParams chp=new LinearLayout.LayoutParams(-1,0,1f); chp.topMargin=dp(8);
        root.addView(contentHost,chp);
        nearbyPanel=buildNearbyPanel();
        scannerPanel=buildScannerPanel();
        dfPanel=buildDfPanel();
        contentHost.addView(nearbyPanel);
        contentHost.addView(scannerPanel);
        contentHost.addView(dfPanel);
        return root;
    }

    private LinearLayout buildNearbyPanel(){
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top=card();
        top.setPadding(dp(12),dp(10),dp(12),dp(10));
        top.addView(text("Nearby BTS / Telecom Sites",16,C_TEXT,true));
        TextView d=text("Tanpa token API. Data lokasi diambil dari OpenStreetMap melalui Overpass. BTS seluler yang memiliki tag mobile_phone diprioritaskan; tower komunikasi umum ditandai sebagai kandidat telecom.",11,C_MUTED,false);
        d.setPadding(0,dp(4),0,dp(8)); top.addView(d);

        LinearLayout controls=new LinearLayout(this);
        radiusInput=input("Radius km");
        radiusInput.setText("3");
        radiusInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        controls.addView(radiusInput,new LinearLayout.LayoutParams(0,dp(48),0.45f));

        Button load=actionButton("LOAD NEARBY",C_BLUE);
        load.setOnClickListener(v->loadNearbyFromOsm());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1f); lp.leftMargin=dp(6);
        controls.addView(load,lp);

        Button locate=actionButton("MY LOCATION",C_CARD2);
        locate.setOnClickListener(v->withLocation(loc->{
            js(String.format(Locale.US,"setCurrent(%f,%f)",loc.getLatitude(),loc.getLongitude()));
            Toast.makeText(this,"Posisi diperbarui.",Toast.LENGTH_SHORT).show();
        }));
        LinearLayout.LayoutParams lp2=new LinearLayout.LayoutParams(0,dp(48),0.8f); lp2.leftMargin=dp(6);
        controls.addView(locate,lp2);
        top.addView(controls);

        nearbyStatus=text("Siap mengambil data terbuka.",12,C_CYAN,true);
        nearbyStatus.setPadding(0,dp(8),0,0);
        top.addView(nearbyStatus);
        TextView att=text("Sumber: OpenStreetMap contributors / Overpass API",10,C_MUTED,false);
        att.setPadding(0,dp(5),0,0); top.addView(att);
        panel.addView(top);

        ScrollView sv=new ScrollView(this);
        nearbyList=text("Tekan LOAD NEARBY. Catatan: kelengkapan data mengikuti kontribusi OpenStreetMap di area tersebut.",12,C_TEXT,false);
        nearbyList.setPadding(dp(4),dp(10),dp(4),dp(20));
        sv.addView(nearbyList);
        panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        return panel;
    }

    private LinearLayout buildScannerPanel(){
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top=card(); top.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout r=new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(text("Passive Cell Scanner",16,C_TEXT,true),new LinearLayout.LayoutParams(0,-2,1f));
        Button scan=actionButton("SCAN NOW",C_BLUE); scan.setOnClickListener(v->scanCells());
        r.addView(scan,new LinearLayout.LayoutParams(dp(120),dp(44))); top.addView(r);
        riskView=text("Risk: belum ada data",17,C_MUTED,true); riskView.setPadding(0,dp(8),0,dp(3)); top.addView(riskView);
        summaryView=text("Membaca serving/neighboring cell dari modem Android dan menilai pola anomali.",11,C_MUTED,false); top.addView(summaryView);
        panel.addView(top);
        ScrollView sv=new ScrollView(this);
        cellsView=text("Cell details akan tampil di sini.",12,C_TEXT,false); cellsView.setPadding(dp(4),dp(10),dp(4),dp(20));
        sv.addView(cellsView); panel.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        return panel;
    }

    private LinearLayout buildDfPanel(){
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout c=card(); c.setPadding(dp(12),dp(10),dp(12),dp(10));
        c.addView(text("Direction Finder",16,C_TEXT,true));
        TextView d=text("Masukkan azimuth dari perangkat RF/SDR/DF pada minimal dua posisi berbeda. Mode HP-only memakai perubahan kekuatan sinyal untuk estimasi area.",11,C_MUTED,false);
        d.setPadding(0,dp(4),0,dp(8)); c.addView(d);
        LinearLayout row=new LinearLayout(this);
        bearingInput=input("Azimuth 0–359°");
        bearingInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        row.addView(bearingInput,new LinearLayout.LayoutParams(0,dp(48),1f));
        Button add=actionButton("ADD BEARING",C_RED); add.setOnClickListener(v->addBearing());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(140),dp(48)); ap.leftMargin=dp(6);
        row.addView(add,ap); c.addView(row);
        Button clear=actionButton("CLEAR DF / MEASUREMENTS",C_CARD2); clear.setOnClickListener(v->clearSession());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(44)); cp.topMargin=dp(6); c.addView(clear,cp);
        dfStatus=text("Belum ada bearing.",12,C_CYAN,true); dfStatus.setPadding(0,dp(8),0,0); c.addView(dfStatus);
        panel.addView(c);
        return panel;
    }

    private void showTab(String tab){
        nearbyPanel.setVisibility(tab.equals("nearby")?View.VISIBLE:View.GONE);
        scannerPanel.setVisibility(tab.equals("scanner")?View.VISIBLE:View.GONE);
        dfPanel.setVisibility(tab.equals("df")?View.VISIBLE:View.GONE);
        styleTab(tabNearby,tab.equals("nearby"));
        styleTab(tabScanner,tab.equals("scanner"));
        styleTab(tabDf,tab.equals("df"));
    }

    private void loadNearbyFromOsm(){
        String rs=radiusInput.getText().toString().trim();
        double km=3;
        try { km=Double.parseDouble(rs); } catch(Exception ignored){}
        km=Math.max(0.3,Math.min(10.0,km));
        final int radius=(int)Math.round(km*1000);
        nearbyStatus.setText("Mengambil posisi perangkat…");
        globalStatus.setText("● FETCHING");
        globalStatus.setTextColor(C_CYAN);
        withLocation(loc->{
            js(String.format(Locale.US,"setCurrent(%f,%f)",loc.getLatitude(),loc.getLongitude()));
            fetchOverpass(loc.getLatitude(),loc.getLongitude(),radius);
        });
    }

    private void fetchOverpass(double lat,double lon,int radius){
        nearbyStatus.setText("Query OpenStreetMap / Overpass…");
        new Thread(()->{
            try{
                String q="[out:json][timeout:25];("+
                        "nwr(around:"+radius+","+lat+","+lon+")[\"communication:mobile_phone\"=\"yes\"];"+
                        "nwr(around:"+radius+","+lat+","+lon+")[\"technology:mobile_phone\"];"+
                        "nwr(around:"+radius+","+lat+","+lon+")[\"telecom\"=\"antenna\"];"+
                        "nwr(around:"+radius+","+lat+","+lon+")[\"tower:type\"=\"communication\"][\"man_made\"~\"mast|tower|communications_tower\"];"+
                        ");out center tags;";
                String encoded=URLEncoder.encode(q,"UTF-8");
                String[] endpoints={
                        "https://overpass-api.de/api/interpreter?data="+encoded,
                        "https://overpass.kumi.systems/api/interpreter?data="+encoded
                };
                String body=null;
                Exception last=null;
                for(String ep:endpoints){
                    try{
                        HttpURLConnection c=(HttpURLConnection)new URL(ep).openConnection();
                        c.setConnectTimeout(12000); c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent","BTSGuardDF/0.2 Android");
                        int code=c.getResponseCode();
                        if(code>=200&&code<300){
                            BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));
                            StringBuilder sb=new StringBuilder(); String line;
                            while((line=br.readLine())!=null) sb.append(line);
                            br.close(); body=sb.toString(); break;
                        }
                    }catch(Exception e){ last=e; }
                }
                if(body==null) throw last!=null?last:new Exception("Overpass unavailable");
                parseOverpass(body,lat,lon);
            }catch(Exception e){
                runOnUiThread(()->{
                    nearbyStatus.setText("Gagal mengambil OSM: "+e.getClass().getSimpleName());
                    globalStatus.setText("● READY"); globalStatus.setTextColor(C_GREEN);
                    Toast.makeText(this,"Overpass sedang sibuk/tidak tersedia. Coba lagi.",Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void parseOverpass(String body,double myLat,double myLon)throws Exception{
        JSONObject root=new JSONObject(body);
        JSONArray elements=root.optJSONArray("elements");
        ArrayList<SiteRecord> sites=new ArrayList<>();
        if(elements!=null){
            for(int i=0;i<elements.length();i++){
                JSONObject e=elements.getJSONObject(i);
                double lat=e.has("lat")?e.optDouble("lat",Double.NaN):e.optJSONObject("center")!=null?e.optJSONObject("center").optDouble("lat",Double.NaN):Double.NaN;
                double lon=e.has("lon")?e.optDouble("lon",Double.NaN):e.optJSONObject("center")!=null?e.optJSONObject("center").optDouble("lon",Double.NaN):Double.NaN;
                if(Double.isNaN(lat)||Double.isNaN(lon)) continue;
                JSONObject t=e.optJSONObject("tags");
                if(t==null) t=new JSONObject();
                boolean mobile="yes".equalsIgnoreCase(t.optString("communication:mobile_phone")) || t.has("technology:mobile_phone");
                String operator=firstNonEmpty(t.optString("operator"),t.optString("network"),t.optString("brand"));
                String name=firstNonEmpty(t.optString("name"),operator,mobile?"Mobile BTS":"Telecom Site");
                String tech=firstNonEmpty(t.optString("technology:mobile_phone"),technologyFromTags(t));
                String structure=firstNonEmpty(t.optString("man_made"),t.optString("telecom"),"site");
                String height=t.optString("height");
                float[] dist=new float[1]; Location.distanceBetween(myLat,myLon,lat,lon,dist);
                sites.add(new SiteRecord(lat,lon,name,operator,tech,structure,height,mobile,dist[0]));
            }
        }
        Collections.sort(sites,Comparator.comparingDouble(s->s.distanceM));
        if(sites.size()>100) sites=new ArrayList<>(sites.subList(0,100));
        final ArrayList<SiteRecord> out=sites;
        runOnUiThread(()->renderSites(out));
    }

    private void renderSites(List<SiteRecord> sites){
        js("clearDatabaseCells()");
        int mobileCount=0;
        StringBuilder list=new StringBuilder();
        int idx=1;
        for(SiteRecord s:sites){
            if(s.mobile) mobileCount++;
            String type=s.mobile?"Mobile BTS":"Telecom candidate";
            String tech=s.tech.isEmpty()?"unknown":s.tech;
            String op=s.operator.isEmpty()?"operator unknown":s.operator;
            String detail=type+" • "+op+" • tech "+tech+" • "+String.format(Locale.US,"%.2f km",s.distanceM/1000.0);
            String radio=radioForTechnology(tech);
            js(String.format(Locale.US,"addDatabaseCell(%f,%f,'%s','%s','%s',0,%s)",
                    s.lat,s.lon,jsEscape(radio),jsEscape(s.name),jsEscape(detail),s.mobile?"true":"false"));
            if(idx<=50){
                list.append(idx++).append(". ").append(s.mobile?"[BTS] ":"[TEL] ").append(s.name).append("\n")
                        .append("   ").append(op).append(" • ").append(tech).append(" • ")
                        .append(String.format(Locale.US,"%.2f km",s.distanceM/1000.0)).append("\n")
                        .append("   ").append(s.structure);
                if(!s.height.isEmpty()) list.append(" • height ").append(s.height);
                list.append("\n\n");
            }
        }
        js("fitDatabaseCells()");
        nearbyStatus.setText(sites.size()+" site ditemukan • "+mobileCount+" ditandai mobile BTS");
        nearbyList.setText(sites.isEmpty()?"Tidak ada telecom site OSM yang terdata dalam radius ini. Coba radius lebih besar.":list.toString());
        globalStatus.setText("● READY"); globalStatus.setTextColor(C_GREEN);
    }

    private String technologyFromTags(JSONObject t){
        ArrayList<String> a=new ArrayList<>();
        if("yes".equalsIgnoreCase(t.optString("communication:5g"))) a.add("5G");
        if("yes".equalsIgnoreCase(t.optString("communication:lte"))) a.add("LTE");
        if("yes".equalsIgnoreCase(t.optString("communication:umts"))) a.add("UMTS");
        if("yes".equalsIgnoreCase(t.optString("communication:gsm"))) a.add("GSM");
        return join(a,"/");
    }

    private String radioForTechnology(String tech){
        String x=tech.toUpperCase(Locale.US);
        if(x.contains("5G")||x.contains("NR")) return "NR";
        if(x.contains("LTE")||x.contains("4G")) return "LTE";
        if(x.contains("UMTS")||x.contains("3G")||x.contains("WCDMA")) return "UMTS";
        if(x.contains("GSM")||x.contains("2G")) return "GSM";
        return "TEL";
    }

    private void scanCells(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestNeededPermissions(); return;
        }
        Location loc=getBestLastLocation();
        if(loc!=null) js(String.format(Locale.US,"setCurrent(%f,%f)",loc.getLatitude(),loc.getLongitude()));
        List<CellInfo> infos;
        try{ infos=telephony.getAllCellInfo(); }catch(SecurityException e){ Toast.makeText(this,"Permission telephony/location belum tersedia.",Toast.LENGTH_LONG).show(); return; }
        if(infos==null||infos.isEmpty()){
            riskView.setText("Risk: CellInfo tidak tersedia");
            summaryView.setText("Aktifkan precise location, SIM, dan mobile network lalu coba lagi.");
            return;
        }
        String simPlmn="";
        try{ simPlmn=telephony.getSimOperator(); }catch(Exception ignored){}
        ArrayList<CellRecord> records=new ArrayList<>();
        for(CellInfo ci:infos){ CellRecord r=parseCell(ci); if(r!=null) records.add(r); }
        if(records.isEmpty()) return;
        Collections.sort(records,Comparator.comparing((CellRecord r)->!r.registered).thenComparingInt(r->-r.dbm));
        CellRecord serving=null; int neighbors=0;
        for(CellRecord r:records){ if(r.registered&&serving==null) serving=r; else neighbors++; }
        if(serving==null) serving=records.get(0);
        RiskResult risk=scoreRisk(serving,neighbors,simPlmn,loc);
        renderRisk(risk);
        StringBuilder detail=new StringBuilder();
        detail.append("SIM PLMN: ").append(simPlmn.isEmpty()?"unknown":simPlmn).append("\n");
        detail.append("Cells visible: ").append(records.size()).append("\n\n");
        for(CellRecord r:records){
            detail.append(r.registered?"[SERVING] ":"[NEIGHBOR] ").append(r.rat).append("  ")
                    .append(r.mcc).append("-").append(r.mnc).append("  ").append(r.dbm).append(" dBm\n")
                    .append("  ").append(r.extra).append("\n\n");
        }
        cellsView.setText(detail.toString());
        if(loc!=null){
            signalSamples.add(new SignalSample(loc.getLatitude(),loc.getLongitude(),serving.dbm));
            js(String.format(Locale.US,"addCellSample(%f,%f,%d,'%s')",loc.getLatitude(),loc.getLongitude(),serving.dbm,jsEscape(serving.rat+" "+serving.mcc+"-"+serving.mnc)));
            updateSignalGradientEstimate();
        }
        previousRat=serving.rat; previousTac=serving.tac; previousLocation=loc; previousScanMs=System.currentTimeMillis();
    }

    private CellRecord parseCell(CellInfo ci){
        try{
            if(ci instanceof CellInfoNr){
                CellInfoNr n=(CellInfoNr)ci; CellIdentityNr id=(CellIdentityNr)n.getCellIdentity(); CellSignalStrengthNr ss=(CellSignalStrengthNr)n.getCellSignalStrength();
                String extra="NCI="+id.getNci()+" TAC="+id.getTac()+" PCI="+id.getPci()+" NRARFCN="+id.getNrarfcn()+" SS-RSRP="+ss.getSsRsrp()+" SS-RSRQ="+ss.getSsRsrq();
                return new CellRecord("5G NR",safe(id.getMccString()),safe(id.getMncString()),id.getTac(),ss.getDbm(),ci.isRegistered(),extra);
            }
            if(ci instanceof CellInfoLte){
                CellInfoLte l=(CellInfoLte)ci; CellIdentityLte id=l.getCellIdentity(); CellSignalStrengthLte ss=l.getCellSignalStrength();
                String extra="CI="+id.getCi()+" TAC="+id.getTac()+" PCI="+id.getPci()+" EARFCN="+id.getEarfcn()+" RSRP="+ss.getRsrp()+" RSRQ="+ss.getRsrq();
                return new CellRecord("4G LTE",safe(id.getMccString()),safe(id.getMncString()),id.getTac(),ss.getDbm(),ci.isRegistered(),extra);
            }
            if(ci instanceof CellInfoWcdma){
                CellInfoWcdma w=(CellInfoWcdma)ci; CellIdentityWcdma id=w.getCellIdentity(); CellSignalStrengthWcdma ss=w.getCellSignalStrength();
                return new CellRecord("3G WCDMA",safe(id.getMccString()),safe(id.getMncString()),id.getLac(),ss.getDbm(),ci.isRegistered(),"CID="+id.getCid()+" LAC="+id.getLac()+" PSC="+id.getPsc()+" UARFCN="+id.getUarfcn());
            }
            if(ci instanceof CellInfoGsm){
                CellInfoGsm g=(CellInfoGsm)ci; CellIdentityGsm id=g.getCellIdentity(); CellSignalStrengthGsm ss=g.getCellSignalStrength();
                return new CellRecord("2G GSM",safe(id.getMccString()),safe(id.getMncString()),id.getLac(),ss.getDbm(),ci.isRegistered(),"CID="+id.getCid()+" LAC="+id.getLac()+" ARFCN="+id.getArfcn()+" BSIC="+id.getBsic());
            }
        }catch(Exception ignored){}
        return null;
    }

    private RiskResult scoreRisk(CellRecord s,int neighbors,String simPlmn,Location loc){
        int score=0; ArrayList<String> reasons=new ArrayList<>(); String cellPlmn=s.mcc+s.mnc;
        if(!simPlmn.isEmpty()&&!s.mcc.equals("?")&&!s.mnc.equals("?")&&!cellPlmn.equals(simPlmn)){score+=35;reasons.add("Serving PLMN berbeda dari SIM/operator");}
        if(s.dbm>-55){score+=20;reasons.add("Serving signal sangat kuat (> -55 dBm)");}
        if(neighbors<=1){score+=10;reasons.add("Neighbor cell sangat sedikit");}
        long now=System.currentTimeMillis();
        if(previousRat!=null&&now-previousScanMs<120000&&isHighRat(previousRat)&&!isHighRat(s.rat)){score+=25;reasons.add("Downgrade cepat dari 4G/5G ke 2G/3G");}
        if(previousTac!=null&&s.tac!=null&&!previousTac.equals(s.tac)&&loc!=null&&previousLocation!=null&&loc.distanceTo(previousLocation)<300f){score+=20;reasons.add("TAC/LAC berubah pada perpindahan <300 m");}
        score=Math.min(100,score);
        if(reasons.isEmpty()) reasons.add("Belum ada indikator anomali kuat dari data pasif");
        return new RiskResult(score,reasons);
    }

    private void renderRisk(RiskResult r){
        String level; int c;
        if(r.score>=70){level="HIGH";c=C_RED;}else if(r.score>=40){level="MEDIUM";c=C_AMBER;}else{level="LOW";c=C_GREEN;}
        riskView.setText("Suspected Rogue BTS Risk: "+r.score+"/100 — "+level); riskView.setTextColor(c);
        StringBuilder sb=new StringBuilder();
        for(String x:r.reasons) sb.append("• ").append(x).append("\n");
        sb.append("\nSkor adalah indikator anomali, bukan bukti forensik BTS palsu.");
        summaryView.setText(sb.toString());
    }

    private void addBearing(){
        String t=bearingInput.getText().toString().trim();
        if(t.isEmpty()){Toast.makeText(this,"Masukkan bearing.",Toast.LENGTH_SHORT).show();return;}
        double b; try{b=Double.parseDouble(t);}catch(Exception e){Toast.makeText(this,"Bearing tidak valid.",Toast.LENGTH_SHORT).show();return;}
        if(b<0||b>=360){Toast.makeText(this,"Gunakan 0 sampai <360°.",Toast.LENGTH_SHORT).show();return;}
        final double bearing=b;
        withLocation(loc->{
            BearingSample s=new BearingSample(loc.getLatitude(),loc.getLongitude(),bearing); bearingSamples.add(s);
            double[] end=destinationPoint(s.lat,s.lng,bearing,5000);
            js(String.format(Locale.US,"addBearing(%f,%f,%f,%f,%.1f)",s.lat,s.lng,end[0],end[1],bearing));
            if(bearingSamples.size()>=2){
                BearingSample a=bearingSamples.get(bearingSamples.size()-2), c=bearingSamples.get(bearingSamples.size()-1);
                double[] p=intersectBearingsApprox(a,c);
                if(p!=null){js(String.format(Locale.US,"showDfTarget(%f,%f)",p[0],p[1]));dfStatus.setText(String.format(Locale.US,"Intersection estimate: %.5f, %.5f",p[0],p[1]));}
                else dfStatus.setText("Bearing hampir paralel / intersection tidak stabil.");
            }else dfStatus.setText("Bearing pertama tersimpan. Ambil bearing kedua dari posisi berbeda.");
            bearingInput.setText("");
        });
    }

    private void updateSignalGradientEstimate(){
        if(signalSamples.size()<2)return;
        ArrayList<SignalSample> sorted=new ArrayList<>(signalSamples); sorted.sort((a,b)->Integer.compare(b.dbm,a.dbm));
        int n=Math.min(5,sorted.size()); double lat=0,lng=0,ws=0;
        for(int i=0;i<n;i++){SignalSample s=sorted.get(i);double w=Math.max(1,140+s.dbm);lat+=s.lat*w;lng+=s.lng*w;ws+=w;}
        lat/=ws;lng/=ws;int radius=Math.max(80,500-Math.min(400,signalSamples.size()*35));
        js(String.format(Locale.US,"showEstimate(%f,%f,%d)",lat,lng,radius));
    }

    private void clearSession(){
        signalSamples.clear(); bearingSamples.clear(); previousRat=null; previousTac=null; previousLocation=null; previousScanMs=0;
        if(riskView!=null){riskView.setText("Risk: belum ada data");riskView.setTextColor(C_MUTED);}
        if(summaryView!=null)summaryView.setText("Session scanner dibersihkan.");
        if(cellsView!=null)cellsView.setText("Cell details akan tampil di sini.");
        if(dfStatus!=null)dfStatus.setText("Belum ada bearing.");
        js("clearMeasurements()");
    }

    private void withLocation(LocationCallback cb){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();return;}
        Location last=getBestLastLocation();
        if(last!=null&&System.currentTimeMillis()-last.getTime()<120000){cb.onLocation(last);return;}
        try{
            String provider=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)?LocationManager.GPS_PROVIDER:LocationManager.NETWORK_PROVIDER;
            nearbyStatus.setText("Menunggu fix lokasi…");
            LocationListener l=new LocationListener(){
                @Override public void onLocationChanged(Location location){locationManager.removeUpdates(this);cb.onLocation(location);}
                @Override public void onProviderEnabled(String p){}
                @Override public void onProviderDisabled(String p){}
                @Override public void onStatusChanged(String p,int s,Bundle e){}
            };
            locationManager.requestLocationUpdates(provider,0,0,l);
            if(last!=null) cb.onLocation(last);
        }catch(Exception e){
            if(last!=null)cb.onLocation(last);else Toast.makeText(this,"Lokasi belum tersedia. Aktifkan GPS.",Toast.LENGTH_LONG).show();
        }
    }

    private Location getBestLastLocation(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;
        Location best=null;
        try{
            for(String p:locationManager.getProviders(true)){
                Location l=locationManager.getLastKnownLocation(p);
                if(l!=null&&(best==null||l.getAccuracy()<best.getAccuracy()))best=l;
            }
        }catch(SecurityException ignored){}
        return best;
    }

    private void requestNeededPermissions(){
        ArrayList<String> n=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)n.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)n.add(Manifest.permission.READ_PHONE_STATE);
        if(!n.isEmpty())requestPermissions(n.toArray(new String[0]),REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_PERMS){
            for(int r:grantResults)if(r!=PackageManager.PERMISSION_GRANTED){Toast.makeText(this,"Precise Location diperlukan untuk fitur BTS sekitar dan CellInfo.",Toast.LENGTH_LONG).show();break;}
        }
    }

    private double[] destinationPoint(double lat,double lon,double bearingDeg,double distanceM){
        double R=6371000,br=Math.toRadians(bearingDeg),p1=Math.toRadians(lat),l1=Math.toRadians(lon),ad=distanceM/R;
        double p2=Math.asin(Math.sin(p1)*Math.cos(ad)+Math.cos(p1)*Math.sin(ad)*Math.cos(br));
        double l2=l1+Math.atan2(Math.sin(br)*Math.sin(ad)*Math.cos(p1),Math.cos(ad)-Math.sin(p1)*Math.sin(p2));
        return new double[]{Math.toDegrees(p2),Math.toDegrees(l2)};
    }

    private double[] intersectBearingsApprox(BearingSample a,BearingSample b){
        double lat0=Math.toRadians((a.lat+b.lat)/2),mpl=111320,mpo=111320*Math.cos(lat0);
        double ax=a.lng*mpo,ay=a.lat*mpl,bx=b.lng*mpo,by=b.lat*mpl,ar=Math.toRadians(a.bearing),br=Math.toRadians(b.bearing);
        double adx=Math.sin(ar),ady=Math.cos(ar),bdx=Math.sin(br),bdy=Math.cos(br),det=adx*(-bdy)-ady*(-bdx);
        if(Math.abs(det)<0.05)return null;
        double rx=bx-ax,ry=by-ay,ta=(rx*(-bdy)-ry*(-bdx))/det,tb=(adx*ry-ady*rx)/det;
        if(ta<0||tb<0||ta>20000||tb>20000)return null;
        return new double[]{(ay+ta*ady)/mpl,(ax+ta*adx)/mpo};
    }

    private void js(String code){
        if(!mapReady){pendingJs.add(code);return;}
        map.post(()->map.evaluateJavascript("javascript:"+code,null));
    }

    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(round(C_CARD,16,Color.rgb(30,41,59),1));return l;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(100,116,139));e.setTextColor(C_TEXT);e.setSingleLine(true);e.setPadding(dp(12),0,dp(12),0);e.setBackground(round(C_CARD2,12,Color.rgb(51,65,85),1));return e;}
    private Button actionButton(String s,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(round(bg,12,bg,0));return b;}
    private Button tabButton(String s){Button b=actionButton(s,C_CARD2);return b;}
    private void styleTab(Button b,boolean active){b.setTextColor(active?Color.WHITE:C_MUTED);b.setBackground(round(active?C_BLUE:C_CARD2,12,active?C_BLUE:Color.rgb(51,65,85),active?0:1));}
    private GradientDrawable round(int fill,int radius,int stroke,int sw){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(sw>0)g.setStroke(dp(sw),stroke);return g;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private static String safe(String s){return s==null||s.isEmpty()?"?":s;}
    private String jsEscape(String s){return s.replace("\\","\\\\").replace("'","\\'").replace("\n"," ");}
    private boolean isHighRat(String r){return r.contains("LTE")||r.contains("NR");}
    private static String firstNonEmpty(String...x){for(String s:x)if(s!=null&&!s.trim().isEmpty())return s.trim();return "";}
    private static String join(List<String>x,String sep){StringBuilder b=new StringBuilder();for(String s:x){if(b.length()>0)b.append(sep);b.append(s);}return b.toString();}

    interface LocationCallback{void onLocation(Location location);}

    static class CellRecord{
        final String rat,mcc,mnc,extra;final Integer tac;final int dbm;final boolean registered;
        CellRecord(String rat,String mcc,String mnc,Integer tac,int dbm,boolean registered,String extra){this.rat=rat;this.mcc=mcc;this.mnc=mnc;this.tac=tac;this.dbm=dbm;this.registered=registered;this.extra=extra;}
    }
    static class RiskResult{final int score;final List<String> reasons;RiskResult(int s,List<String>r){score=s;reasons=r;}}
    static class SignalSample{final double lat,lng;final int dbm;SignalSample(double a,double b,int d){lat=a;lng=b;dbm=d;}}
    static class BearingSample{final double lat,lng,bearing;BearingSample(double a,double b,double c){lat=a;lng=b;bearing=c;}}
    static class SiteRecord{
        final double lat,lon;final String name,operator,tech,structure,height;final boolean mobile;final float distanceM;
        SiteRecord(double lat,double lon,String name,String operator,String tech,String structure,String height,boolean mobile,float distanceM){this.lat=lat;this.lon=lon;this.name=name;this.operator=operator;this.tech=tech;this.structure=structure;this.height=height;this.mobile=mobile;this.distanceM=distanceM;}
    }
}

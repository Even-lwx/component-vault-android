package cn.componentvault.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.JSONTokener;

/** 通过可见商城页面读取公开资料，不保存账号密码，也不绕过商城验证。 */
public final class LcscLookupActivity extends ComponentActivity {
    private WebView web;
    private TextView status;
    private String code;
    private boolean reading;
    private boolean confirming;
    private boolean automatic=true;
    private boolean waitingForLogin;
    private int attempts;
    private int generation;
    private int scriptRequest;
    private long readStarted;
    private String extractionScript;
    private String lastError="等待商品内容";
    private final LookupWindow window=new LookupWindow();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        code=getIntent().getStringExtra("code");
        if(!LcscProductParser.validCode(code)) { setResult(RESULT_CANCELED); finish(); return; }
        try(java.io.InputStream in=getAssets().open("lcsc-detail.js")) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            extractionScript=out.toString("UTF-8");
        } catch(java.io.IOException e) { finish(); return; }
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16,40,16,20);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30) {
                var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); root.setPadding(16,bars.top+8,16,bars.bottom+8);
            }
            return insets;
        });
        status=new TextView(this); status.setText("正在按编号查询 "+code+"，将自动查找编号一致的商品并读取参数。若出现商城验证，请在页面完成；你仍需确认采用资料。"); status.setPadding(8,8,8,8); root.addView(status);
        web=new WebView(this);
        var settings=web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon) {
                pageStarted(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                return !allowed(request.getUrl());
            }
            @Override public void onPageFinished(WebView view,String url) {
                if(!url.equals(view.getUrl()))return;
                // 资源完成只更新状态；读取循环从 onPageStarted 启动，不重置截止时间。
                if(waitingForLogin && !"passport.jlc.com".equals(Uri.parse(url).getHost()))pageStarted(url);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                if(request.isForMainFrame()) {lastError="主页面加载失败："+error.getDescription(); status.setText(lastError+"。可重试查询或返回手动填写。");}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);
        Button cancel=new Button(this); cancel.setText("返回"); cancel.setOnClickListener(v->finish()); actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
        Button retry=new Button(this); retry.setText("重试查询"); retry.setOnClickListener(v->search()); actions.addView(retry,new LinearLayout.LayoutParams(0,-2,1));
        Button read=new Button(this); read.setText("继续查询/读取"); read.setOnClickListener(v->{automatic=true;confirming=false;attempts=0;window.begin(now());generation++;scriptRequest++;reading=false;poll(generation);}); actions.addView(read,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions); setContentView(root); search();
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }
    private void search() {
        handler.removeCallbacksAndMessages(null);automatic=true;confirming=false;waitingForLogin=false;attempts=0;generation++;scriptRequest++;reading=false;
        window.begin(now());lastError="等待商品页面";
        status.setText("正在查询 "+code+"；读取商品内容，无需等待广告和图片全部加载…");
        web.loadUrl("https://so.szlcsc.com/global.html?k="+code);
        poll(generation); // 首个页面甚至没触发加载回调时，也有截止时间。
    }
    private void pageStarted(String url) {
        if(isFinishing() || isDestroyed())return;
        if(!allowed(Uri.parse(url)))return;
        scriptRequest++;reading=false;generation++;handler.removeCallbacksAndMessages(null);
        if("passport.jlc.com".equals(Uri.parse(url).getHost())) {
            waitingForLogin=true;automatic=false;window.pause();
            status.setText("商城要求登录，请在此官方页面自行操作；登录后继续查询，或返回手动填写。");return;
        }
        if(waitingForLogin){waitingForLogin=false;automatic=true;window.resume(now());}
        if(automatic)poll(generation);
    }
    private static boolean allowed(Uri uri) {
        String host=uri.getHost();
        return "https".equals(uri.getScheme()) && host!=null && (host.equals("szlcsc.com") || host.endsWith(".szlcsc.com") || host.equals("passport.jlc.com"));
    }

    private void poll(int ticket) {
        if(isDestroyed() || isFinishing() || ticket!=generation || !automatic || confirming)return;
        if(window.expired(now())) {
            automatic=false;
            scriptRequest++;reading=false;
            status.setText("读取超时（60秒）："+lastError+"。商品页面已打开也不代表参数表已取得，可点“继续查询/读取”、重试或返回手动填写。");return;
        }
        attempts++;
        if(reading && now()-readStarted>4000) {scriptRequest++;reading=false;lastError="页面脚本暂未响应，已重新读取";}
        Uri current=Uri.parse(web.getUrl()==null?"":web.getUrl());
        if("item.szlcsc.com".equals(current.getHost())) read(true);
        else if("so.szlcsc.com".equals(current.getHost())) {
            String script="(function(){var code="+org.json.JSONObject.quote(code)+";var matches=new Set();for(var a of document.querySelectorAll('a[href]')){var u;try{u=new URL(a.href)}catch(e){continue}if(u.protocol!=='https:'||u.hostname!=='item.szlcsc.com'||!/^\\/\\d+\\.html$/.test(u.pathname))continue;var p=a;for(var i=0;i<6&&p;i++,p=p.parentElement){var t=(p.innerText||'');if(t.length>14000)break;var codes=t.match(/C\\d{4,12}/g)||[];var unique=Array.from(new Set(codes));if(unique.length===1&&unique[0]===code){matches.add(u.origin+u.pathname);break;}}}return matches.size===1?Array.from(matches)[0]:'';})()";
            web.evaluateJavascript(script,result->{
                if(ticket!=generation || isDestroyed() || !automatic)return;
                try {
                    Object decoded=new JSONTokener(result).nextValue();
                    if(decoded instanceof String && !((String)decoded).isEmpty()) {
                        Uri uri=Uri.parse((String)decoded);
                        if(allowed(uri) && "item.szlcsc.com".equals(uri.getHost())) {
                            status.setText("已找到 "+code+"，正在读取商品详情参数…");web.loadUrl(uri.toString());return;
                        }
                    }
                }catch(Exception ignored){}
            });
        }
        handler.postDelayed(()->poll(ticket),1000);
    }

    private void read(boolean auto) {
        if(reading || confirming) return;
        Uri url=Uri.parse(web.getUrl()==null?"":web.getUrl());
        if(!allowed(url) || !"item.szlcsc.com".equals(url.getHost())) {
            status.setText("请先点击搜索结果中的商品型号，进入商品详情页；搜索页和推荐商品不会被导入。"); return;
        }
        reading=true;
        readStarted=now();final int request=++scriptRequest;final int ticket=generation;
        final String requestedPage=web.getUrl();
        web.evaluateJavascript(extractionScript,result->{
            if(request!=scriptRequest || ticket!=generation || !automatic)return;
            reading=false;
            if(isDestroyed() || isFinishing()) return;
            try {
                if(!requestedPage.equals(web.getUrl())) throw new IllegalArgumentException("页面正在切换，请等待后重新读取");
                Object decoded=new JSONTokener(result).nextValue();
                if(!(decoded instanceof String)) throw new IllegalArgumentException("页面未加载完成，请稍后再读取");
                String text=(String)decoded;
                if(text.isEmpty())throw new IllegalArgumentException("商品参数表尚未生成，正在继续检查");
                Part p=LcscProductParser.parse(text,code);
                confirming=true; automatic=false;
                String source=Uri.parse(requestedPage).buildUpon().clearQuery().fragment(null).build().toString();
                if(getIntent().getBooleanExtra("directEntry",false)) {
                    setResult(RESULT_OK,new Intent().putExtra("text",text).putExtra("code",code).putExtra("url",source));finish();return;
                }
                new AlertDialog.Builder(this).setTitle("核对立创商品资料 · "+code)
                        .setMessage(PartDisplay.category(p)+"\n"+PartDisplay.primary(p)+"\n"+PartDisplay.ratings(p)+"\n"+PartDisplay.reference(p)+"\n\n将以商品参数更正录入表单中对应的非空字段；未提供的参数保留你的原值。实际库存数量和存放位置不会被覆盖。")
                        .setNegativeButton("继续查看",(d,w)->confirming=false).setOnCancelListener(d->confirming=false).setPositiveButton("采用商城资料",(d,w)->{
                            setResult(RESULT_OK,new Intent().putExtra("text",text).putExtra("code",code).putExtra("url",source)); finish();
                        }).show();
            } catch(Exception e) {
                lastError=e.getMessage()==null?"商品读取失败":e.getMessage();
                status.setText("正在读取 "+code+"："+lastError+"（第 "+attempts+" 次检查，可返回或重试）");
            }
        });
    }

    @Override protected void onDestroy() { generation++;handler.removeCallbacksAndMessages(null);if(web!=null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}

package cn.componentvault.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends ComponentActivity {
    private static final int INK=0xff19362f, MUTED=0xff72857c, GREEN=0xff11776a, BG=0xfff4f7f5;
    private InventoryStore store;
    private LinearLayout root, content, inventoryRows;
    private TextView stats;
    private TextView resultsLabel;
    private LinearLayout categoryChips;
    private HorizontalScrollView categoryScroll;
    private EditText searchBox;
    private Button filterButton;
    private final InventoryBrowser.Filter browse=new InventoryBrowser.Filter();
    private List<Part> browserParts=new ArrayList<>();
    private Set<String> pinned=new HashSet<>();
    private int categoryOrder;
    private boolean hideEmpty;
    private int shownItems=80;
    private String category="全部", query="", photoPath="", exportText="";
    private boolean recognizing;
    private AlertDialog formDialog;
    private final LinkedHashMap<String,EditText> inputs = new LinkedHashMap<>();
    private final Map<String,CheckBox> conflictChecks=new HashMap<>();
    private Part draft;
    private long lookupPartId=-1;
    private String pendingScanQr="";

    private final ActivityResultLauncher<Intent> scanCatalog=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
        String qr=pendingScanQr;pendingScanQr="";
        if(result.getResultCode()!=RESULT_OK||result.getData()==null) {
            if(!qr.isEmpty())new AlertDialog.Builder(this).setTitle("商城资料尚未取得")
                .setMessage("可以重新扫码查询，或仅凭二维码编号手动填写。未取得的商品参数不会由 OCR 猜测。")
                .setNegativeButton("返回",null).setPositiveButton("手动填写",(d,w)->showForm(QrPayload.parse(qr,store.categories()))).show();
            return;
        }
        try {
            Part source=QrPayload.parse(qr,store.categories());
            String code=result.getData().getStringExtra("code");
            if(!source.code.equals(code))throw new IllegalArgumentException("扫码编号与商城结果不一致，请重扫");
            Part product=LcscProductParser.parse(result.getData().getStringExtra("text"),code);
            product.quantity=source.quantity;
            product.raw="[二维码]\n"+qr+"\n[立创商品资料]\n"+result.getData().getStringExtra("url")+"\n"+product.description;
            showForm(product);
        }catch(Exception e){error("商城资料未导入",e);}
    });

    private final ActivityResultLauncher<Intent> lookup=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
        long existingId=lookupPartId; lookupPartId=-1;
        if(result.getResultCode()!=RESULT_OK || result.getData()==null) return;
        try {
            String code=result.getData().getStringExtra("code");
            Part p=LcscProductParser.parse(result.getData().getStringExtra("text"),code);
            if(existingId>=0) {
                store.applyCatalog(existingId,p,result.getData().getStringExtra("url"));
                renderHome(); showDetail(existingId); toast("已用商城资料校准物料，实际库存数量保持不变"); return;
            }
            if(draft==null || inputs.isEmpty() || !val("code").equalsIgnoreCase(code)) throw new IllegalArgumentException("表单编号已更改，请按当前编号重新查询");
            StringBuilder provenance=new StringBuilder("\n[立创商品资料]\n").append(result.getData().getStringExtra("url")).append('\n');
            for(var field:LcscProductParser.fields(p).entrySet()) {
                inputs.get(field.getKey()).setText(field.getValue());
                if(conflictChecks.containsKey(field.getKey())) conflictChecks.get(field.getKey()).setChecked(true);
                provenance.append(field.getKey()).append(": ").append(field.getValue()).append('\n');
            }
            provenance.append(p.description);
            draft.raw+=provenance;
            inputs.get("description").setText(p.description);
            toast("已按立创商品资料补齐参数；请核对后入库，数量仍使用你的实际库存");
        } catch(Exception e) { error("商城资料未导入",e); }
    });

    private final ActivityResultLauncher<Intent> scan = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode()==RESULT_OK && result.getData()!=null) {
            String qr=result.getData().getStringExtra("qr");
            Part p=QrPayload.parse(qr,store.categories());
            if(!LcscProductParser.validCode(p.code)){toast("未读到完整立创编号，请重扫");return;}
            pendingScanQr=qr;
            scanCatalog.launch(new Intent(this,LcscLookupActivity.class).putExtra("code",p.code).putExtra("directEntry",true));
        }
    });
    private final ActivityResultLauncher<String> album = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri!=null) {
            try {
                File dest = newPhoto();
                try (InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(dest)) {
                    if (in==null) throw new IOException("无法读取所选照片");
                    byte[] buffer=new byte[16384]; int count;
                    while ((count=in.read(buffer))!=-1) out.write(buffer,0,count);
                }
                photoPath=dest.getAbsolutePath(); recognize(Uri.fromFile(dest));
            } catch (Exception e) { error("照片读取失败",e); }
        }
    });
    private final ActivityResultLauncher<Uri> camera = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
        if (success) recognize(Uri.fromFile(new File(photoPath)));
    });
    private final ActivityResultLauncher<String> permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
        if (granted) takePhoto(); else toast("未获得相机权限，可以使用相册或手动录入");
    });
    private final ActivityResultLauncher<String> export = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
        if (uri==null) return;
        try (OutputStream out=getContentResolver().openOutputStream(uri)) {
            if (out==null) throw new IOException("无法打开目标文件");
            out.write(exportText.getBytes(StandardCharsets.UTF_8)); toast("导出完成");
        } catch (Exception e) { error("导出失败",e); }
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new InventoryStore(this);
        var preferences=getPreferences(MODE_PRIVATE);
        pinned=new HashSet<>(preferences.getStringSet("pinnedCategories",Collections.emptySet()));
        categoryOrder=preferences.getInt("categoryOrder",0);hideEmpty=preferences.getBoolean("hideEmptyCategories",true);
        browse.sort=Math.max(0,Math.min(InventoryBrowser.SORT_NAMES.length-1,preferences.getInt("inventorySort",0)));
        browse.stock=Math.max(0,Math.min(3,preferences.getInt("stockFilter",0)));browse.footprint=preferences.getString("footprintFilter","");browse.color=preferences.getString("colorFilter","");browse.location=preferences.getString("locationFilter","");
        if (state!=null) {
            lookupPartId=state.getLong("lookupPartId",-1);
            pendingScanQr=state.getString("pendingScanQr","");
            photoPath=state.getString("photo",""); exportText=state.getString("export","");
            query=state.getString("query",""); category=state.getString("category","全部");
        }
        renderHome();
        if (state!=null && state.containsKey("draft")) {
            Part p=LabelParser.parse("");
            applyDraft(p,state.getBundle("draft")); showForm(p);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("lookupPartId",lookupPartId);
        state.putString("pendingScanQr",pendingScanQr);
        state.putString("photo",photoPath); state.putString("export",exportText);
        state.putString("query",query); state.putString("category",category);
        if (formDialog!=null && formDialog.isShowing() && draft!=null) {
            Bundle b=new Bundle(); for (String key:inputs.keySet()) b.putString(key,inputs.get(key).getText().toString());
            b.putString("raw",draft.raw); b.putString("photo",draft.photo); state.putBundle("draft",b);
        }
    }

    private void renderHome() {
        root=column(); root.setBackgroundColor(BG);
        root.setPadding(dp(20),dp(12),dp(20),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if (android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(dp(20),bars.top+dp(12),dp(20),bars.bottom);
            }
            return insets;
        });
        LinearLayout heading=row();
        LinearLayout titles=column(); titles.addView(text("COMPONENT VAULT",11,GREEN,true));
        titles.addView(text("我的元件仓",28,INK,true)); heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Button more=button("导出",false); more.setOnClickListener(v->exportMenu()); heading.addView(more);
        root.addView(heading);
        stats=text("",14,MUTED,false); stats.setPadding(0,dp(10),0,dp(20)); root.addView(stats);

        LinearLayout hero=row(); hero.setPadding(dp(16),dp(12),dp(16),dp(12)); hero.setBackground(shape(INK,18));
        LinearLayout heroText=column();heroText.addView(text("扫码即入库",19,Color.WHITE,true));heroText.addView(text("二维码 → 商城参数 → 核对保存",11,0xffbfd7cc,false));hero.addView(heroText,new LinearLayout.LayoutParams(0,-2,1));
        Button scanButton=button("扫一扫",true); scanButton.setOnClickListener(v->scan.launch(new Intent(this,ScanActivity.class))); hero.addView(scanButton);
        root.addView(hero);

        LinearLayout actions=row(); actions.setPadding(0,dp(10),0,dp(14));
        Button photo=button("拍照 / 相册",false), manual=button("＋ 手动录入",false);
        photo.setOnClickListener(v->photoMenu()); manual.setOnClickListener(v->showForm(new Part()));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(52),1); half.setMargins(0,0,dp(8),0);
        actions.addView(photo,half); actions.addView(manual,new LinearLayout.LayoutParams(0,dp(52),1));
        Button bom=button("BOM 配料",false);bom.setOnClickListener(v->startActivity(new Intent(this,BomActivity.class)));actions.addView(bom,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(actions);

        LinearLayout searchRow=row();EditText search=edit("搜索：100nF 0402、红色、型号…");searchBox=search;search.setSingleLine(true); search.setText(query); searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
        Button clear=button("清空",false);clear.setOnClickListener(v->search.setText(""));searchRow.addView(clear);root.addView(searchRow);
        HorizontalScrollView filters=new HorizontalScrollView(this); filters.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=row(); chips.setPadding(0,dp(12),0,dp(12));categoryChips=chips;categoryScroll=filters;
        if(!category.equals("全部") && !store.categories().contains(category)) category="全部";
        filters.addView(chips); root.addView(filters);
        LinearLayout toolbar=row();
        Button sort=button("排序 ▾",false);sort.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("库存排序").setSingleChoiceItems(InventoryBrowser.SORT_NAMES,browse.sort,(d,i)->{browse.sort=i;saveBrowsePreferences();d.dismiss();refresh();}).setNegativeButton("取消",null).show());toolbar.addView(sort,new LinearLayout.LayoutParams(0,-2,1));
        filterButton=button("筛选 ▾",false);filterButton.setOnClickListener(v->browseFilters());toolbar.addView(filterButton,new LinearLayout.LayoutParams(0,-2,1));
        Button manage=button("分类设置",false); manage.setOnClickListener(v->categorySettings());toolbar.addView(manage,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);
        resultsLabel=text("",12,MUTED,false);resultsLabel.setPadding(0,dp(8),0,dp(4));root.addView(resultsLabel);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); inventoryRows=column(); scroll.addView(inventoryRows);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=text("分类角标为物料种数 · 长按分类可置顶",11,MUTED,false); footer.setGravity(Gravity.CENTER); footer.setPadding(0,dp(7),0,dp(7)); root.addView(footer);
        setContentView(root); refresh();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { query=s.toString();shownItems=80;refresh(); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void refresh() {
        browserParts=store.search("","全部");
        long[] counts=store.stats(); stats.setText(InventoryBrowser.kinds(browserParts)+" 种物料   ·   "+counts[1]+" 个库存   ·   "+counts[0]+" 个库位记录");
        renderCategories();
        inventoryRows.removeAllViews();
        browse.query=query;browse.category=category;
        List<Part> parts=InventoryBrowser.apply(browserParts,browse);
        String active=join(" · ",browse.footprint,browse.color,browse.location,browse.stock==0?"":new String[]{"","有库存","已用尽","低库存≤10"}[browse.stock]);
        resultsLabel.setText("找到 "+InventoryBrowser.kinds(parts)+" 种 / "+parts.size()+" 条   ·   "+InventoryBrowser.SORT_NAMES[browse.sort]+(active.isEmpty()?"":"\n筛选："+active));
        filterButton.setText("筛选"+(browse.extraCount()>0?" ("+browse.extraCount()+")":"")+" ▾");
        if (parts.isEmpty()) {
            TextView empty=text(browserParts.isEmpty()?"还没有物料\n\n扫描标签，开始整理你的元件仓。":"没有符合条件的物料\n\n尝试减少关键词或重置筛选。",16,MUTED,false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(12),dp(35),dp(12),dp(24)); inventoryRows.addView(empty);
            if(browse.active()) {Button reset=button("重置全部筛选",true);reset.setOnClickListener(v->resetBrowse());inventoryRows.addView(reset);}return;
        }
        for (Part p:parts.subList(0,Math.min(shownItems,parts.size()))) {
            LinearLayout card=column(); card.setPadding(dp(16),dp(14),dp(16),dp(14)); card.setBackground(shape(Color.WHITE,16));card.setElevation(dp(1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(10),0,0); inventoryRows.addView(card,lp);
            String title=PartDisplay.category(p);
            LinearLayout top=row(); top.addView(text(title,18,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            TextView quantity=text(p.quantity+" 个",16,p.quantity==0?0xffb33f38:p.quantity<=10?0xffa76a22:GREEN,true);quantity.setPadding(dp(10),dp(5),dp(10),dp(5));quantity.setBackground(shape(p.quantity<=10?0xfffff0df:0xffe8f5ef,10));top.addView(quantity);card.addView(top);
            String firstLine=PartDisplay.primary(p);
            if(!firstLine.isEmpty()) {
                TextView value=text(firstLine,20,GREEN,true); value.setPadding(0,dp(8),0,0); card.addView(value);
            }
            TextView ratings=text(PartDisplay.ratings(p),13,INK,false);
            ratings.setPadding(0,dp(8),0,dp(4)); if(!p.footprint.isEmpty() || !p.voltage.isEmpty() || !p.power.isEmpty() || !p.current.isEmpty()) card.addView(ratings);
            TextView meta=text(PartDisplay.reference(p),12,MUTED,false); meta.setPadding(0,dp(6),0,dp(8)); if(!meta.getText().toString().isEmpty()) card.addView(meta);
            card.addView(text(p.location.isEmpty()?"尚未设置位置":p.location,12,MUTED,false));
            card.setOnClickListener(v->showDetail(p.id));
        }
        if(parts.size()>shownItems) {
            Button more=button("再显示 80 条 · 剩余 "+(parts.size()-shownItems)+" 条",false);
            more.setOnClickListener(v->{shownItems+=80;refresh();});inventoryRows.addView(more);
        }
    }

    private void saveBrowsePreferences() {
        getPreferences(MODE_PRIVATE).edit().putInt("inventorySort",browse.sort).putInt("stockFilter",browse.stock)
                .putString("footprintFilter",browse.footprint).putString("colorFilter",browse.color).putString("locationFilter",browse.location)
                .putInt("categoryOrder",categoryOrder).putBoolean("hideEmptyCategories",hideEmpty).putStringSet("pinnedCategories",new HashSet<>(pinned)).apply();
    }
    private void renderCategories() {
        int scroll=categoryScroll.getScrollX();categoryChips.removeAllViews();
        Map<String,Integer> counts=InventoryBrowser.categoryCounts(browserParts);Map<String,Long> visits=new HashMap<>();
        for(String name:store.categories())visits.put(name,getPreferences(MODE_PRIVATE).getLong("categoryVisit:"+name,0));
        List<String> names=new ArrayList<>();names.add("全部");names.addAll(InventoryBrowser.categories(store.categories(),counts,pinned,visits,categoryOrder,hideEmpty,category));
        for(String name:names) {
            boolean selected=name.equals(category);int count=name.equals("全部")?InventoryBrowser.kinds(browserParts):counts.getOrDefault(name,0);
            TextView chip=text((pinned.contains(name)?"★ ":"")+name+"  "+count+"种",13,selected?Color.WHITE:INK,selected);
            chip.setPadding(dp(15),dp(10),dp(15),dp(10));chip.setBackground(shape(selected?GREEN:Color.WHITE,20));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.setMargins(0,0,dp(8),0);categoryChips.addView(chip,params);
            chip.setOnClickListener(v->{
                category=name;shownItems=80;getPreferences(MODE_PRIVATE).edit().putLong("categoryVisit:"+name,System.currentTimeMillis()).apply();
                refresh(); // 保留输入焦点和横向滚动位置，不重建首页。
            });
            if(!name.equals("全部"))chip.setOnLongClickListener(v->{
                if(!pinned.add(name))pinned.remove(name);saveBrowsePreferences();renderCategories();toast(pinned.contains(name)?"已置顶“"+name+"”":"已取消置顶");return true;
            });
        }
        categoryScroll.post(()->categoryScroll.scrollTo(scroll,0));
    }
    private void categorySettings() {
        String[] items={"排序："+InventoryBrowser.CATEGORY_SORT_NAMES[categoryOrder],hideEmpty?"显示空分类":"隐藏空分类","管理分类名称 / 新增 / 删除"};
        new AlertDialog.Builder(this).setTitle("分类显示 · 置顶始终在前")
                .setItems(items,(d,i)->{
                    if(i==0)new AlertDialog.Builder(this).setTitle("分类排序方式").setSingleChoiceItems(InventoryBrowser.CATEGORY_SORT_NAMES,categoryOrder,(dialog,which)->{
                        categoryOrder=which;saveBrowsePreferences();dialog.dismiss();renderCategories();
                    }).setNegativeButton("取消",null).show();
                    else if(i==1){hideEmpty=!hideEmpty;saveBrowsePreferences();renderCategories();}
                    else manageCategories();
                }).setNegativeButton("关闭",null).show();
    }
    private void browseFilters() {
        LinearLayout box=column();box.setPadding(dp(22),dp(8),dp(22),dp(8));
        box.addView(text("所有条件同时满足 · 数量≤10 为低库存\n分类计数为整个库存种数，不随筛选变化。",12,MUTED,false));
        Spinner availability=new Spinner(this);availability.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"全部库存","仅有库存","仅已用尽","低库存：1～10 个"}));availability.setSelection(browse.stock);
        box.addView(text("库存状态",13,INK,true));box.addView(availability);
        Map<String,Spinner> selectors=new LinkedHashMap<>();Map<String,List<String>> options=new HashMap<>();
        String[] fields={"footprint","color","location"},labels={"封装","颜色 / 光色","存放位置"},current={browse.footprint,browse.color,browse.location};
        for(int i=0;i<fields.length;i++) {
            List<String> values=new ArrayList<>();values.add("不限");values.addAll(InventoryBrowser.choices(browserParts,fields[i],"全部"));
            if(!current[i].isEmpty()&&!values.contains(current[i]))values.add(current[i]);
            box.addView(text(labels[i],13,INK,true));Spinner spinner=new Spinner(this);
            spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));spinner.setSelection(current[i].isEmpty()?0:values.indexOf(current[i]));box.addView(spinner);selectors.put(fields[i],spinner);options.put(fields[i],values);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("组合筛选")
                .setView(scroll).setNegativeButton("取消",null).setNeutralButton("重置全部",(d,w)->resetBrowse())
                .setPositiveButton("应用筛选",(d,w)->{
                    browse.stock=availability.getSelectedItemPosition();
                    String[] selected=new String[3];for(int i=0;i<fields.length;i++){int at=selectors.get(fields[i]).getSelectedItemPosition();selected[i]=at==0?"":options.get(fields[i]).get(at);}
                    browse.footprint=selected[0];browse.color=selected[1];browse.location=selected[2];saveBrowsePreferences();refresh();
                }).show();
    }
    private void resetBrowse() {
        category="全部";query="";shownItems=80;browse.stock=0;browse.footprint="";browse.color="";browse.location="";saveBrowsePreferences();
        if(searchBox!=null&&!searchBox.getText().toString().isEmpty())searchBox.setText("");else refresh();
    }

    private void showForm(Part p) {
        if (isFinishing() || isDestroyed()) return;
        draft=p; inputs.clear(); conflictChecks.clear(); content=column(); content.setPadding(dp(22),dp(8),dp(22),dp(16));
        TextView hint=text(p.raw.contains("[立创商品资料]")?"商品资料来自立创商城，未提供的字段留空。请核对实际数量与存放位置，再确认入库。":p.raw.isEmpty()?"可按型号、编码或主要参数建档。":"请重点核对阻值、容值和感值；未读到时可手动填写，未知参数不猜测。",13,MUTED,false);
        hint.setPadding(0,0,0,dp(12)); content.addView(hint);
        CheckBox autoLookup=new CheckBox(this);autoLookup.setText("拍照/相册读到二维码时也自动查询（扫一扫始终直查）");autoLookup.setTextSize(13);
        autoLookup.setChecked(getPreferences(MODE_PRIVATE).getBoolean("autoCatalog",true));
        autoLookup.setOnCheckedChangeListener((v,checked)->getPreferences(MODE_PRIVATE).edit().putBoolean("autoCatalog",checked).apply());content.addView(autoLookup);
        Button online=button("按物料编号查询立创参数",true);
        online.setOnClickListener(v->{
            String code=val("code").toUpperCase(Locale.ROOT);
            if(!LcscProductParser.validCode(code)) { inputs.get("code").setError("先填完整编号，例如 C489136，再点查询"); inputs.get("code").requestFocus(); return; }
            inputs.get("code").setText(code);
            lookupPartId=-1;
            lookup.launch(new Intent(this,LcscLookupActivity.class).putExtra("code",code));
        }); content.addView(online);
        if(!p.photo.isEmpty()) {
            Button image=button("对照原标签照片",false); image.setOnClickListener(v->viewPhoto(p.photo)); content.addView(image);
        }
        field("category","类别 · 这是什么元器件",p.category,false);
        if(LabelCategories.truncated(p.category)) {
            TextView warning=text("类别有省略，暂未唯一匹配；请选择完整类别或手动补全。",12,0xffb66b40,true); content.addView(warning);
        }
        Button choose=button("选择分类 / 管理分类",false); choose.setOnClickListener(v->showCategoryPicker(inputs.get("category"))); content.addView(choose);
        field("resistance","电阻阻值（例如 10kΩ、4K7、2R2）",p.resistance,false);
        field("capacitance","电容容值（例如 100nF、4.7uF）",p.capacitance,false);
        field("inductance","电感感值（例如 4.7uH、10mH）",p.inductance,false);
        field("color","颜色 / 光色（例如红色、暖白）",p.color,false);
        field("footprint","封装",p.footprint,false);
        field("voltage","耐压 / 额定电压（例如 50V、1kV）",p.voltage,false);
        field("power","额定功率（例如 0.25W、1/4W）",p.power,false);
        field("current","电流（例如 1A、500mA）",p.current,false);
        field("model","型号",p.model,false); field("code","物料编码",p.code,false);
        field("platform","采购平台 / 供应商（未知时手动填写）",p.platform,false);
        field("quantity","本次入库数量（实际剩余数量）",p.quantity>0?Long.toString(p.quantity):"",true);
        field("brand","品牌",p.brand,false);
        field("description","规格参数",p.description,false); field("location","存放位置，例如 A柜 / 03格",p.location,false); field("note","备注",p.note,false);
        if (!p.raw.isEmpty()) {
            TextView raw=text("查看识别原文",13,GREEN,true); raw.setPadding(0,dp(14),0,dp(8));
            raw.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("识别原文").setMessage(p.raw).setPositiveButton("关闭",null).show()); content.addView(raw);
        }
        ScrollView sc=new ScrollView(this); sc.addView(content);
        formDialog=new AlertDialog.Builder(this).setTitle(p.raw.isEmpty()?"手动入库":"核对后入库").setView(sc).setNegativeButton("取消",null).setPositiveButton("确认入库",null).create();
        formDialog.setOnShowListener(d->formDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->saveForm())); formDialog.show();
    }

    private void field(String key,String label,String value,boolean number) {
        TextView caption=text(label,12,MUTED,true); caption.setPadding(0,dp(10),0,dp(6)); content.addView(caption);
        EditText e=edit(label); e.setText(value); e.setSingleLine(!key.equals("note") && !key.equals("description"));
        if (number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        inputs.put(key,e); content.addView(e,new LinearLayout.LayoutParams(-1,-2));
        List<String> candidates=OcrCandidates.read(draft.raw,key);
        if(draft.raw.contains("source."+key+": 二维码")) {
            TextView source=text("来源：二维码（仍可手动更正）",12,GREEN,false); content.addView(source);
        }
        if(candidates.size()>1) {
            TextView alternatives=text("识别有分歧 · 点击选择候选",12,0xffb66b40,true);
            alternatives.setPadding(0,dp(8),0,dp(6));
            alternatives.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(label+" · 请对照标签选择")
                    .setItems(candidates.toArray(new String[0]),(d,i)->e.setText(candidates.get(i)))
                    .setNegativeButton("手动填写",null).show());
            content.addView(alternatives);
            if(Arrays.asList("resistance","capacitance","inductance","voltage","power","current","model","quantity").contains(key)) {
                CheckBox checked=new CheckBox(this); checked.setText("已对照标签 / 商城资料核对这个字段"); checked.setTextSize(12);
                conflictChecks.put(key,checked); content.addView(checked);
            }
        }
    }

    private String val(String key) { return inputs.get(key).getText().toString().trim(); }

    private void saveForm() {
        for(var entry:conflictChecks.entrySet()) if(!entry.getValue().isChecked()) {
            inputs.get(entry.getKey()).setError("识别存在分歧，请选择或修改正确值，并勾选已核对");
            inputs.get(entry.getKey()).requestFocus(); toast("有分歧的参数必须核对后再保存"); return;
        }
        Part p=new Part(); p.model=val("model"); p.code=val("code"); p.platform=val("platform"); p.brand=val("brand"); p.footprint=val("footprint");
        p.category=LabelCategories.complete(val("category"),store.categories()); if(p.category.isEmpty()) p.category="其他";
        if(LabelCategories.truncated(p.category)) {
            inputs.get("category").setError("这个省略名称对应多个类别，请选择完整类别后再保存");
            return;
        }
        try {
            p.resistance=parameterValue("resistance","电阻");
            p.capacitance=parameterValue("capacitance","电容");
            p.inductance=parameterValue("inductance","电感");
        } catch(IllegalArgumentException e) { return; }
        p.color=val("color");
        try {
            p.voltage=ratedValue("voltage"); p.power=ratedValue("power"); p.current=ratedValue("current");
        } catch(IllegalArgumentException e) { return; }
        p.description=val("description"); p.location=val("location"); p.note=val("note"); p.raw=draft.raw; p.photo=draft.photo;
        if(p.model.isEmpty() && p.code.isEmpty() && p.resistance.isEmpty() && p.capacitance.isEmpty() && p.inductance.isEmpty()) { inputs.get("model").setError("型号、编码或主要参数至少填一项"); return; }
        try { p.quantity=Long.parseLong(val("quantity")); if(p.quantity<=0 || p.quantity>InventoryStore.MAX_QUANTITY) throw new NumberFormatException(); }
        catch(NumberFormatException e) { inputs.get("quantity").setError("请输入 1 至 10 亿之间的整数"); return; }
        try { store.ensureCategory(p.category); }
        catch(Exception e) { inputs.get("category").setError(e.getMessage()); return; }
        Part existing=store.duplicate(p);
        if(existing!=null) {
            new AlertDialog.Builder(this).setTitle("已有相同物料和位置")
                .setMessage("现有 "+existing.quantity+" 个，本次补货 "+p.quantity+" 个。\n确认后增加库存，保留已有资料，并补全原先为空的平台、型号、品牌与封装。")
                .setNegativeButton("返回核对",null).setPositiveButton("确认补货",(d,w)->receive(p)).show();
        } else receive(p);
    }

    private void receive(Part p) {
        try { store.receive(p); formDialog.dismiss(); renderHome(); toast("已入库 "+p.quantity+" 个"); }
        catch(Exception e) { error("入库失败",e); }
    }

    private String parameterValue(String field,String category) {
        return parameterValue(inputs.get(field),field,category);
    }

    private String parameterValue(EditText input,String field,String category) {
        String value=input.getText().toString().trim();
        if(value.isEmpty()) return "";
        String canonical=PassiveValues.first(value,field,category);
        if(canonical.isEmpty()) { input.setError("请填写数值与单位，例如 10kΩ / 100nF / 4.7uH"); throw new IllegalArgumentException(); }
        return canonical;
    }

    private String ratedValue(String field) {
        return ratedValue(inputs.get(field),field);
    }

    private String ratedValue(EditText input,String field) {
        String value=input.getText().toString().trim(); if(value.isEmpty()) return "";
        String canonical=ElectricalValues.first(value,field);
        if(canonical.isEmpty()) { input.setError("请填写带单位的额定值，例如 1kV、0.25W、500mA"); throw new IllegalArgumentException(); }
        return canonical;
    }

    private void showCategoryPicker(EditText target) {
        List<String> names=store.categories();
        String[] options=names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("选择分类").setItems(options,(dialog,which)->target.setText(options[which]))
                .setNeutralButton("管理分类",(dialog,which)->manageCategories()).show();
    }

    private void manageCategories() {
        List<String> names=store.categories();
        String[] options=names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("管理分类 · 点击改名或删除")
                .setItems(options,(dialog,which)->categoryActions(options[which]))
                .setNeutralButton("＋ 新增分类",(dialog,which)->categoryNameDialog(null))
                .setPositiveButton("完成",(dialog,which)->renderHome()).show();
    }

    private void categoryActions(String name) {
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"改名","删除"},(dialog,which)->{
            if(which==0) categoryNameDialog(name); else deleteCategoryDialog(name);
        }).show();
    }

    private void categoryNameDialog(String oldName) {
        EditText name=edit("分类名称，例如一次性保险丝");
        if(oldName!=null) name.setText(oldName);
        LinearLayout box=column(); box.setPadding(dp(24),dp(8),dp(24),0); box.addView(name);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(oldName==null?"新增分类":"修改分类名称")
                .setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String value=name.getText().toString().trim();
                if(oldName==null) store.addCategory(value); else store.renameCategory(oldName,value);
                if(category.equals(oldName)) category=value;
                if(oldName!=null&&pinned.remove(oldName)){pinned.add(value);saveBrowsePreferences();}
                if(oldName!=null && inputs.containsKey("category") && inputs.get("category").getText().toString().equals(oldName)) inputs.get("category").setText(value);
                dialog.dismiss(); renderHome(); manageCategories();
            } catch(Exception e) { name.setError(e.getMessage()); }
        })); dialog.show();
    }

    private void deleteCategoryDialog(String name) {
        List<String> replacements=new ArrayList<>(store.categories()); replacements.removeIf(s->s.equalsIgnoreCase(name));
        if(replacements.isEmpty()) { toast("至少保留一个分类"); return; }
        new AlertDialog.Builder(this).setTitle("删除“"+name+"”")
                .setItems(replacements.toArray(new String[0]),(dialog,which)->{
                    String replacement=replacements.get(which);
                    new AlertDialog.Builder(this).setTitle("确认删除分类？")
                            .setMessage("该分类下的物料会转到“"+replacement+"”，库存和流水会保留。")
                            .setNegativeButton("取消",null).setPositiveButton("删除并转移",(d,w)->{
                                try {
                                    store.deleteCategory(name,replacement);
                                    pinned.remove(name);saveBrowsePreferences();
                                    if(inputs.containsKey("category") && inputs.get("category").getText().toString().equals(name)) inputs.get("category").setText(replacement);
                                    if(category.equalsIgnoreCase(name)) category="全部";
                                    renderHome(); manageCategories();
                                } catch(Exception e) { error("删除失败",e); }
                            }).show();
                }).setNegativeButton("取消",null).show();
    }

    private void showDetail(long id) {
        Part p=store.get(id); if(p==null) return;
        LinearLayout body=column(); body.setPadding(dp(24),dp(8),dp(24),dp(18));
        if(!PartDisplay.primary(p).isEmpty()) body.addView(text(PartDisplay.primary(p),26,GREEN,true));
        body.addView(text("库存 "+p.quantity+" 个",15,MUTED,false));
        TextView data=text(PartDisplay.detail(p),14,INK,false);
        data.setTextIsSelectable(true); data.setLineSpacing(dp(7),1); data.setPadding(0,dp(14),0,dp(16)); body.addView(data);
        if(!p.photo.isEmpty() && new File(p.photo).exists()) {
            Button image=button("查看标签照片",false); image.setOnClickListener(v->viewPhoto(p.photo)); body.addView(image);
        }
        if(!p.raw.isEmpty()) {
            Button raw=button("查看原始识别内容",false); raw.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("原始识别内容").setMessage(p.raw).setPositiveButton("关闭",null).show()); body.addView(raw);
        }
        Button recategorize=button("更改物料分类",false); body.addView(recategorize);
        Button parameters=button("补录 / 更正阻值、容值、感值",false); body.addView(parameters);
        Button electrical=button("更正颜色、耐压、功率、电流",false); body.addView(electrical);
        Button catalog=button("按编号联网校准立创参数",false); body.addView(catalog);
        TextView title=text("最近出入库记录（最多 100 条）",14,INK,true); title.setPadding(0,dp(18),0,dp(8)); body.addView(title);
        for(String line:store.history(id)) { TextView item=text(line,13,MUTED,false); item.setPadding(0,dp(8),0,dp(8)); body.addView(item); }
        Button delete=button("删除物料及全部出入库记录",false); delete.setTextColor(0xffb33f38); delete.setPadding(0,dp(8),0,dp(8)); body.addView(delete);
        ScrollView sc=new ScrollView(this); sc.addView(body);
        AlertDialog detail=new AlertDialog.Builder(this).setTitle(PartDisplay.category(p)).setView(sc)
                .setNegativeButton("关闭",null).setNeutralButton("领用出库",null).setPositiveButton("补货入库",null).create();
        detail.setOnShowListener(d->{
            detail.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->adjust(p,false,detail));
            detail.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->adjust(p,true,detail));
        }); detail.show();
        catalog.setOnClickListener(v->{
            String code=p.code.trim().toUpperCase(Locale.ROOT);
            if(!LcscProductParser.validCode(code)) { toast("这条物料没有完整立创编号，请在新录入页面填写编号后查询"); return; }
            lookupPartId=p.id; detail.dismiss(); lookup.launch(new Intent(this,LcscLookupActivity.class).putExtra("code",code));
        });
        delete.setOnClickListener(v->confirmDelete(p,detail));
        electrical.setOnClickListener(v->editElectricalValues(p,detail));
        parameters.setOnClickListener(v->editParameters(p,detail));
        recategorize.setOnClickListener(v->{
            String[] names=store.categories().toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("选择新分类").setItems(names,(dialog,which)->{
                try { store.setCategory(id,names[which]); detail.dismiss(); refresh(); showDetail(id); }
                catch(Exception e) { error("分类修改失败",e); }
            }).setNegativeButton("取消",null).show();
        });
    }

    private void confirmDelete(Part p,AlertDialog detail) {
        String name=!p.model.isEmpty()?p.model:!p.code.isEmpty()?p.code:join(" · ",p.resistance,p.capacitance,p.inductance);
        new AlertDialog.Builder(this).setTitle("确认删除物料？")
                .setMessage("将删除“"+name+"”的库存记录（当前 "+p.quantity+" 个）、全部出入库流水和对应标签照片。此操作无法撤销。\n\n如果只是数量录错，也可以取消后用出入库功能调整。")
                .setNegativeButton("取消",null)
                .setPositiveButton("删除物料",(dialog,which)->{
                    try {
                        String photo=store.deletePart(p.id);
                        boolean photoRemoved=photo==null || photo.isEmpty() || !new File(photo).exists() || new File(photo).delete();
                        detail.dismiss(); renderHome();
                        toast(photoRemoved?"物料和出入库记录已删除":"物料已删除，但标签照片未能清理");
                    } catch(Exception e) { error("删除失败，库存未更改",e); }
                }).show();
    }

    private void editParameters(Part p,AlertDialog detail) {
        LinearLayout box=column(); box.setPadding(dp(24),dp(12),dp(24),0);
        EditText resistance=edit("阻值，例如 10kΩ / 4K7"); resistance.setText(p.resistance);
        EditText capacitance=edit("容值，例如 100nF / 4.7uF"); capacitance.setText(p.capacitance);
        EditText inductance=edit("感值，例如 4.7uH / 10mH"); inductance.setText(p.inductance);
        box.addView(text("阻值",12,MUTED,true)); box.addView(resistance);
        box.addView(text("容值",12,MUTED,true)); box.addView(capacitance);
        box.addView(text("感值",12,MUTED,true)); box.addView(inductance);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("更正主要参数").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                store.setPassiveValues(p.id,parameterValue(resistance,"resistance","电阻"),parameterValue(capacitance,"capacitance","电容"),parameterValue(inductance,"inductance","电感"));
                dialog.dismiss(); detail.dismiss(); refresh(); showDetail(p.id);
            } catch(IllegalArgumentException e) { if(e.getMessage()!=null) toast(e.getMessage()); }
            catch(Exception e) { error("参数保存失败",e); }
        })); dialog.show();
    }

    private void editElectricalValues(Part p,AlertDialog detail) {
        LinearLayout box=column(); box.setPadding(dp(24),dp(12),dp(24),dp(12));
        EditText color=edit("颜色 / 光色"); color.setText(p.color);
        EditText voltage=edit("例如 50V / 1kV"); voltage.setText(p.voltage);
        EditText power=edit("例如 0.25W / 1/4W"); power.setText(p.power);
        EditText current=edit("例如 1A / 500mA"); current.setText(p.current);
        for(int i=0;i<4;i++) {
            box.addView(text(new String[]{"颜色 / 光色","耐压 / 额定电压","额定功率","电流"}[i],12,MUTED,true));
            box.addView(new EditText[]{color,voltage,power,current}[i]);
        }
        ScrollView scroll=new ScrollView(this); scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("更正颜色和电气参数").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                store.setElectricalValues(p.id,color.getText().toString().trim(),ratedValue(voltage,"voltage"),ratedValue(power,"power"),ratedValue(current,"current"));
                dialog.dismiss(); detail.dismiss(); refresh(); showDetail(p.id);
            } catch(IllegalArgumentException e) { if(e.getMessage()!=null) toast(e.getMessage()); }
            catch(Exception e) { error("参数保存失败",e); }
        })); dialog.show();
    }

    private void adjust(Part p,boolean incoming,AlertDialog detail) {
        LinearLayout box=column(); box.setPadding(dp(24),0,dp(24),0);
        EditText count=edit("数量"); count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); box.addView(count);
        EditText reason=edit(incoming?"备注，例如补购":"用途，例如电源板调试"); box.addView(reason);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(incoming?"补货入库":"领用出库").setView(box).setNegativeButton("取消",null).setPositiveButton("确认",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                long n=Long.parseLong(count.getText().toString()); if(n<=0 || n>InventoryStore.MAX_QUANTITY) throw new NumberFormatException();
                String note=reason.getText().toString().trim();
                store.change(p.id,incoming?n:-n,(incoming?"补货":"领用")+(note.isEmpty()?"":" · "+note));
                dialog.dismiss(); detail.dismiss(); refresh(); showDetail(p.id);
            } catch(NumberFormatException e) { count.setError("请输入有效的正整数"); }
            catch(Exception e) { count.setError(e.getMessage()); }
        })); dialog.show();
    }

    private void photoMenu() {
        if(recognizing) { toast("正在识别，请稍候"); return; }
        new AlertDialog.Builder(this).setTitle("识别整张标签").setItems(new String[]{"拍摄标签照片","从相册选择"},(d,i)->{
            if(i==0) permission.launch(Manifest.permission.CAMERA); else album.launch("image/*");
        }).show();
    }

    private File newPhoto() throws IOException {
        File dir=new File(getFilesDir(),"labels"); if(!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建照片目录");
        return File.createTempFile("label_",".jpg",dir);
    }

    private void takePhoto() {
        try { File file=newPhoto(); photoPath=file.getAbsolutePath(); camera.launch(FileProvider.getUriForFile(this,getPackageName()+".files",file)); }
        catch(Exception e) { error("无法启动相机",e); }
    }

    private void recognize(Uri uri) {
        recognizing=true; final String sourcePhoto=photoPath;
        AlertDialog progress=new AlertDialog.Builder(this).setTitle("正在读取标签").setMessage("读取二维码和原图；必要时裁剪文字区域、放大和增强后再识别…").setCancelable(false).create(); progress.show();
        PhotoRecognizer.recognize(this,uri,new PhotoRecognizer.Callback() {
            public void success(PhotoRecognizer.Result result) {
                recognizing=false;if(!isDestroyed())progress.dismiss();
                if(isFinishing()||isDestroyed())return;
                ScanSession session=new ScanSession(store.categories());
                session.observeCandidates(result.texts,result.codes,true,0);
                Part p=LabelParser.parse(session.result(),store.categories()); p.photo=sourcePhoto;
                p.raw+="\n[图像处理] "+result.processing;
                if(!session.hasData()) toast("未读到型号、编码或主要参数，请对照照片填写");
                showRecognized(p);
            }
            public void failure(Exception error) {
                recognizing=false;if(!isDestroyed())progress.dismiss();
                MainActivity.this.error("识别失败，可重新拍摄或手动填写",error);
            }
        });
    }

    private void viewPhoto(String path) {
        try {
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",new File(path));
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch(Exception e) { error("无法打开照片",e); }
    }

    private void showRecognized(Part p) {
        showForm(p);
        boolean qr=p.raw.contains("source.code: 二维码");
        if(!qr || !LcscProductParser.validCode(p.code) || !getPreferences(MODE_PRIVATE).getBoolean("autoCatalog",true))return;
        android.net.ConnectivityManager manager=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        var network=manager.getActiveNetwork();var capabilities=network==null?null:manager.getNetworkCapabilities(network);
        if(capabilities==null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            toast("二维码已解码；当前无网络，保留离线结果，联网后可点查询补齐参数");return;
        }
        lookupPartId=-1;lookup.launch(new Intent(this,LcscLookupActivity.class).putExtra("code",p.code));
    }

    private void exportMenu() {
        new AlertDialog.Builder(this).setTitle("导出库存").setItems(new String[]{"CSV 表格 · 库存清单","JSON 数据 · 库存与完整流水","关于元件仓"},(d,i)->{
            if(i==2) {
                new AlertDialog.Builder(this).setTitle("元件仓 0.13.0").setMessage("扫一扫只解码二维码编号，不运行 OCR。取得编号后自动联网查询立创；编号校验通过、参数表就绪后直接进入录入页，不必等广告和图片加载完成。整次查询超过 60 秒会提示原因，可重试或返回。\n\n商城可能要求登录或验证，缺失字段留空。拍照识别、手动录入和 BOM 匹配仍可离线使用。\n\nBOM 试算不扣库存，暂不提供库存导入恢复或跨设备同步。").setPositiveButton("知道了",null).show(); return;
            }
            try {
                exportText=i==0?store.exportCsv():store.exportJson();
                String date=new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(new Date());
                export.launch("元件仓-"+date+(i==0?".csv":".json"));
            } catch(Exception e) { error("导出失败",e); }
        }).show();
    }

    private void applyDraft(Part p,Bundle b) {
        if(b==null) return;
        p.model=b.getString("model",""); p.code=b.getString("code",""); p.platform=b.getString("platform",""); p.brand=b.getString("brand",""); p.footprint=b.getString("footprint","");
        p.category=b.getString("category",""); p.description=b.getString("description",""); p.location=b.getString("location",""); p.note=b.getString("note",""); p.raw=b.getString("raw",""); p.photo=b.getString("photo","");
        p.resistance=b.getString("resistance",""); p.capacitance=b.getString("capacitance",""); p.inductance=b.getString("inductance","");
        p.color=b.getString("color",""); p.voltage=b.getString("voltage",""); p.power=b.getString("power",""); p.current=b.getString("current","");
        try { p.quantity=Long.parseLong(b.getString("quantity","0")); } catch(NumberFormatException ignored) {}
    }

    private String fallback(String s) { return s.isEmpty()?"—":s; }
    private String join(String delimiter,String... values) { List<String> a=new ArrayList<>(); for(String s:values) if(!s.isEmpty()) a.add(s); return String.join(delimiter,a); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String s,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if(bold)t.setTypeface(null,Typeface.BOLD); return t; }
    private EditText edit(String hint) { EditText e=new EditText(this); e.setHint(hint); e.setTextSize(15); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setPadding(dp(12),dp(12),dp(12),dp(12)); e.setBackground(shape(Color.WHITE,12)); e.setMinHeight(dp(48)); return e; }
    private Button button(String s,boolean primary) { Button b=new Button(this); b.setText(s); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary?Color.WHITE:GREEN); b.setBackground(shape(primary?GREEN:Color.WHITE,12)); b.setPadding(dp(14),dp(10),dp(14),dp(10)); b.setMinHeight(dp(48)); return b; }
    private GradientDrawable shape(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void error(String title,Exception e) { if(!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle(title).setMessage(e.getLocalizedMessage()).setPositiveButton("知道了",null).show(); }
}

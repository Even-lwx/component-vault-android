package cn.componentvault.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** BOM 导入、列映射、匹配与导出。只计算备料，不修改库存。 */
public final class BomActivity extends ComponentActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private InventoryStore store;
    private State state=new State();
    private List<Part> stock=new ArrayList<>();
    private LinearLayout list;
    private TextView summary;
    private EditText setsInput;
    private int page;
    private String snapshot;
    private String exportSnapshot;
    private byte[] pendingExport;
    private boolean busy;
    private static final class State implements Serializable {
        List<BomFiles.Sheet> sheets=new ArrayList<>();
        List<BomMatch.Row> rows=new ArrayList<>();
        List<String> headers=new ArrayList<>();
        Map<String,Integer> mapping=new LinkedHashMap<>();
        BomMatch.Rules rules=new BomMatch.Rules();
        String filename="";
        int sheet,header;
        long sets=1;
    }
    private final ActivityResultLauncher<String[]> pick=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{ if(uri!=null)load(uri); });
    private final ActivityResultLauncher<String> export=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),uri->{
        if(uri==null || pendingExport==null)return;
        byte[] data=pendingExport; pendingExport=null; busy=true;
        worker.execute(()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri)) {
                if(out==null)throw new IOException("无法创建目标文件"); out.write(data);
                runOnUiThread(()->{busy=false; toast("BOM 匹配结果已导出");});
            } catch(Exception e) { runOnUiThread(()->{busy=false; error(e);}); }
        });
    });

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store=new InventoryStore(this);
        if(saved!=null) {
            snapshot=saved.getString("snapshot");
            exportSnapshot=saved.getString("exportSnapshot");
            if(exportSnapshot!=null)try(InputStream in=new FileInputStream(exportSnapshot)){pendingExport=BomFiles.limitedRead(in);}catch(Exception ignored){}
            if(snapshot!=null)try(ObjectInputStream in=new ObjectInputStream(new FileInputStream(snapshot))) { state=(State)in.readObject(); } catch(Exception ignored) { }
        }
        stock=store.search("","全部"); render();
        if(saved==null) {
            Intent intent=getIntent(); Uri uri=null;
            if(Intent.ACTION_SEND.equals(intent.getAction())) uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            else if(Intent.ACTION_VIEW.equals(intent.getAction()))uri=intent.getData();
            if(uri==null && intent.getClipData()!=null && intent.getClipData().getItemCount()>0)uri=intent.getClipData().getItemAt(0).getUri();
            if(uri!=null)load(uri);
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        try {
            if(snapshot==null)snapshot=new File(getCacheDir(),"bom-"+UUID.randomUUID()+".bin").getAbsolutePath();
            try(ObjectOutputStream stream=new ObjectOutputStream(new FileOutputStream(snapshot))) { stream.writeObject(state); }
            out.putString("snapshot",snapshot);
            if(pendingExport!=null) {
                if(exportSnapshot==null)exportSnapshot=new File(getCacheDir(),"bom-export-"+UUID.randomUUID()+".bin").getAbsolutePath();
                try(OutputStream stream=new FileOutputStream(exportSnapshot)){stream.write(pendingExport);}
                out.putString("exportSnapshot",exportSnapshot);
            }
        } catch(Exception ignored) { }
        super.onSaveInstanceState(out);
    }
    private void render() {
        LinearLayout root=column(); root.setPadding(dp(16),dp(20),dp(16),dp(12)); root.setBackgroundColor(0xfff4f7f5);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30) { var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); root.setPadding(dp(16),bars.top+dp(10),dp(16),bars.bottom+dp(8)); }
            return insets;
        });
        root.addView(text("BOM 备料与匹配",24));
        root.addView(text("离线试算 · 不扣减库存 · 匹配不代表电气兼容",12));
        LinearLayout actions=row(); actions.addView(button("导入文件",()->{
            if(busy)return;
            if(!state.rows.isEmpty()) new AlertDialog.Builder(this).setTitle("重新导入 BOM？").setMessage("当前修改会被新文件替换，请先导出需要保留的结果。").setNegativeButton("取消",null).setPositiveButton("选择文件",(d,w)->pick.launch(new String[]{"*/*"})).show();
            else pick.launch(new String[]{"*/*"});
        }),weight());
        actions.addView(button("列 / 工作表",()->{if(!state.sheets.isEmpty()&&!busy)chooseSheet();}),weight());
        actions.addView(button("导出",this::exportMenu),weight()); root.addView(actions);
        root.addView(text(state.filename.isEmpty()?"支持 CSV / TSV / XLSX；旧 XLS 请另存为 XLSX。\n聊天附件可选择“分享/其他应用打开 → 元件仓 BOM”。\n如果微信或钉钉不提供文件 URI，需先使用它提供的保存/分享入口。":state.filename+" · "+state.sheets.get(state.sheet).name,12));
        LinearLayout controls=row(); controls.addView(text("套数",14)); setsInput=new EditText(this); setsInput.setSingleLine(); setsInput.setInputType(2);setsInput.setText(Long.toString(state.sets));controls.addView(setsInput,new LinearLayout.LayoutParams(dp(90),-2));
        controls.addView(button("匹配条件",this::rulesDialog),weight());controls.addView(button("重新匹配",()->calculate(true)),weight());root.addView(controls);
        summary=text("",13);root.addView(summary);
        ScrollView scroll=new ScrollView(this);list=column();scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout paging=row();paging.addView(button("上一页",()->{if(page>0){page--;showRows();}}),weight());paging.addView(button("下一页",()->{if((page+1)*40<state.rows.size()){page++;showRows();}}),weight());paging.addView(button("关闭",this::finish),weight());root.addView(paging);
        setContentView(root);showRows();
    }
    private void load(Uri uri) {
        if(busy)return;
        if(!Arrays.asList("content","file").contains(uri.getScheme())) { error(new IOException("请选择聊天附件或本机文件，不支持远程网址"));return; }
        busy=true;summary.setText("正在读取 BOM 附件…");
        worker.execute(()->{
            try {
                String name="BOM.csv";
                if("content".equals(uri.getScheme()))try(var c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if(c!=null&&c.moveToFirst())name=c.getString(0); }
                else if(uri.getLastPathSegment()!=null)name=uri.getLastPathSegment();
                List<BomFiles.Sheet> sheets;
                try(InputStream in=getContentResolver().openInputStream(uri)){sheets=BomFiles.read(BomFiles.limitedRead(in),name);}
                State next=new State();next.filename=name;next.sheets=sheets;
                runOnUiThread(()->{if(isDestroyed())return;busy=false;state=next;page=0;render();chooseSheet();});
            }catch(Exception e){runOnUiThread(()->{busy=false;error(e);showRows();});}
        });
    }
    private void chooseSheet() {
        if(state.sheets.size()==1){chooseHeader(0);return;}
        String[] names=state.sheets.stream().map(s->s.name).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("选择工作表").setItems(names,(d,i)->chooseHeader(i)).setNegativeButton("取消",null).show();
    }
    private void chooseHeader(int sheet) {
        BomFiles.Sheet s=state.sheets.get(sheet);int count=Math.min(30,s.rows.size());String[] names=new String[count];
        for(int i=0;i<count;i++)names[i]="第 "+(i+1)+" 行："+String.join(" | ",s.rows.get(i));
        new AlertDialog.Builder(this).setTitle("哪一行是表头？").setItems(names,(d,i)->mappingDialog(sheet,i)).setNegativeButton("取消",null).show();
    }
    private void mappingDialog(int sheet,int header) {
        List<String> headers=new ArrayList<>(state.sheets.get(sheet).rows.get(header));
        int columns=headers.size(); for(List<String> r:state.sheets.get(sheet).rows)columns=Math.max(columns,r.size());
        while(headers.size()<columns)headers.add("未命名列 "+(headers.size()+1));
        List<String> choices=new ArrayList<>();choices.add("不使用 / 未提供");for(int i=0;i<headers.size();i++)choices.add((i+1)+". "+headers.get(i));
        LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);box.addView(text("每套数量必须映射；参数列可选择 Value / Comment。导出会保留原始列，并附加你的修改与匹配结果。",13));
        Map<String,Spinner> spinners=new LinkedHashMap<>();
        for(int i=0;i<BomMatch.KEYS.length;i++) {
            box.addView(text(BomMatch.LABELS[i],13));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));
            spinner.setSelection(BomMatch.guess(BomMatch.KEYS[i],headers)+1);box.addView(spinner);spinners.put(BomMatch.KEYS[i],spinner);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("对应 BOM 列").setView(scroll).setNegativeButton("取消",null).setPositiveButton("导入",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            Map<String,Integer> map=new LinkedHashMap<>(); for(var e:spinners.entrySet())map.put(e.getKey(),e.getValue().getSelectedItemPosition()-1);
            if(map.get("quantity")<0){toast("请指定每套数量列，避免用错用量");return;}
            state.sheet=sheet;state.header=header;state.headers=headers;state.mapping=map;
            state.rows=BomMatch.rows(state.sheets.get(sheet),header,map);page=0;dialog.dismiss();render();calculate(true);
        }));dialog.show();
    }
    private void rulesDialog() {
        String[] fields={"value","footprint","color","code","model","category","voltage","power","current"};
        String[] labels={"阻值 / 容值 / 感值","封装（精确匹配，不将 0603 和 1608 直接等同）","颜色","立创编号","型号","类别（精确名称）","电压（精确值）","功率（精确值）","电流（精确值）"};
        Set<String> chosen=new LinkedHashSet<>(state.rules.fields);boolean[] checked=new boolean[fields.length];for(int i=0;i<fields.length;i++)checked[i]=chosen.contains(fields[i]);
        new AlertDialog.Builder(this).setTitle("所有选中条件必须同时满足")
                .setMultiChoiceItems(labels,checked,(d,i,on)->{if(on)chosen.add(fields[i]);else chosen.remove(fields[i]);})
                .setNegativeButton("取消",null).setPositiveButton("采用并重新匹配",(d,w)->{
                    if(chosen.isEmpty()){toast("至少选择一个匹配条件");return;}
                    state.rules.fields.clear();state.rules.fields.addAll(chosen);
                    // 条件变更时清除旧人工选择，避免沿用不再匹配的结果。
                    for(BomMatch.Row r:state.rows){r.manual=false;r.selected=-1;}calculate(true);
                }).show();
    }
    private boolean calculate(boolean rematch) {
        if(busy)return false;
        try {
            long sets=Long.parseLong(setsInput.getText().toString().trim());
            if(sets<1 || sets>1000000)throw new IllegalArgumentException("套数需为 1 至 1000000");
            stock=store.search("","全部");BomMatch.calculate(state.rows,stock,state.rules,sets,rematch);state.sets=sets;showRows();return true;
        }catch(Exception e){error(new IllegalArgumentException("请检查数量、套数和输入范围。"+(e.getMessage()==null?"":e.getMessage())));return false;}
    }
    private Part selected(BomMatch.Row row) { for(Part p:stock)if(p.id==row.selected)return p;return null; }
    private void showRows() {
        if(list==null)return;list.removeAllViews();java.math.BigInteger missing=java.math.BigInteger.ZERO;int need=0,invalid=0;
        for(BomMatch.Row r:state.rows){missing=missing.add(java.math.BigInteger.valueOf(r.shortage));if(r.shortage>0)need++;if(!r.issue.isEmpty())invalid++;}
        summary.setText(state.rows.size()+" 行 · "+state.sets+" 套 · "+need+" 行缺料，共缺 "+missing+" 个 · "+invalid+" 行需修正\n条件："+String.join(" + ",ruleNames())+"。按 BOM 顺序试分配；不扣库存。\n第 "+(page+1)+" / "+Math.max(1,(state.rows.size()+39)/40)+" 页");
        for(int i=page*40;i<Math.min(state.rows.size(),(page+1)*40);i++) {
            BomMatch.Row r=state.rows.get(i);Part p=selected(r);LinearLayout card=column();card.setPadding(dp(12),dp(12),dp(12),dp(16));card.setBackgroundColor(-1);
            card.addView(text((i+1)+". "+r.reference+" · "+r.value,18));
            card.addView(text(r.request.category+" · "+r.request.footprint+" · "+r.request.color+"\n每套 "+r.perSet+" × "+state.sets+" = "+r.required,14));
            TextView result=text(!r.issue.isEmpty()?r.issue:p==null?"未匹配到库存":PartDisplay.category(p)+" / "+PartDisplay.primary(p)+"\n"+PartDisplay.ratings(p)+"\n"+p.model+" "+p.code+" · "+p.location+"\n本行可分配 "+r.allocated+"，缺 "+r.shortage+(r.manual?" · 人工指定":"")+"\n"+BomMatch.allocationText(r,stock),14);
            result.setTextColor(r.shortage>0||!r.issue.isEmpty()?0xffb33f38:0xff11776a);card.addView(result);
            LinearLayout actions=row();actions.addView(button("修改此行",()->editRow(r)),weight());actions.addView(button("选择库存",()->chooseStock(r)),weight());actions.addView(button("不匹配",()->{r.manual=true;r.selected=-1;calculate(false);}),weight());card.addView(actions);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,0);list.addView(card,lp);
        }
    }
    private List<String> ruleNames() {
        List<String> names=Arrays.asList("value","footprint","color","code","model","category","voltage","power","current");
        String[] labels={"主要参数","封装","颜色","编号","型号","类别","电压","功率","电流"};
        List<String> out=new ArrayList<>();for(String field:state.rules.fields)out.add(labels[names.indexOf(field)]);return out;
    }
    private void editRow(BomMatch.Row r) {
        LinearLayout box=column();box.setPadding(dp(20),0,dp(20),0);Map<String,EditText> edits=new LinkedHashMap<>();
        String[] keys={"reference","quantity","category","value","code","model","footprint","color","voltage","power","current"};
        String[] labels={"位号","每套数量","类别","阻值 / 容值 / 感值","编号","型号","封装","颜色","电压","功率","电流"};
        String[] values={r.reference,Long.toString(r.perSet),r.request.category,r.value,r.request.code,r.request.model,r.request.footprint,r.request.color,r.request.voltage,r.request.power,r.request.current};
        for(int i=0;i<keys.length;i++){box.addView(text(labels[i],13));EditText e=new EditText(this);e.setText(values[i]);box.addView(e);edits.put(keys[i],e);}
        ScrollView scroll=new ScrollView(this);scroll.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("修改 BOM 行").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存并匹配",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            try {
                List<String> values2=new ArrayList<>();Map<String,Integer> map=new LinkedHashMap<>();for(String key:keys){map.put(key,values2.size());values2.add(edits.get(key).getText().toString().trim());}
                long q=BomMatch.quantity(edits.get("quantity").getText().toString());
                BomMatch.Row updated=BomMatch.rows(new BomFiles.Sheet("edited",Arrays.asList(Arrays.asList(keys),values2)),0,map).get(0);
                r.reference=updated.reference;r.value=updated.value;r.perSet=q;r.request=updated.request;r.issue="";r.manual=false;r.selected=-1;
                dialog.dismiss();calculate(true);
            }catch(Exception e){toast("每套数量需为 0 至 10 亿的整数，且物料行不能为空");}
        }));dialog.show();
    }
    private void chooseStock(BomMatch.Row r) {
        List<Part> choices=new ArrayList<>();for(Part p:stock)if(BomMatch.matches(r,p,state.rules))choices.add(p);
        stockDialog(r,choices,false);
    }
    private void stockDialog(BomMatch.Row r,List<Part> choices,boolean all) {
        LinearLayout box=column();box.setPadding(dp(16),0,dp(16),0);EditText filter=new EditText(this);filter.setHint("筛选型号 / 参数 / 位置");box.addView(filter);
        LinearLayout options=column();ScrollView scroll=new ScrollView(this);scroll.addView(options);box.addView(scroll,new LinearLayout.LayoutParams(-1,dp(310)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(all?"人工指定库存（请确认兼容性）":"符合条件的库存")
                .setView(box).setNegativeButton("取消",null).setNeutralButton(all?"返回条件匹配":"查看全部库存",(d,w)->{if(all)chooseStock(r);else stockDialog(r,stock,true);}).create();
        Runnable populate=()->{
            options.removeAllViews();int shown=0;
            for(Part p:choices) {
                String label=PartDisplay.category(p)+" · "+PartDisplay.primary(p)+"\n"+PartDisplay.ratings(p)+"\n"+p.code+" "+p.model+" / "+p.location+" / 库存 "+p.quantity;
                if(!BomMatch.norm(label).contains(BomMatch.norm(filter.getText().toString())))continue;
                if(shown++>=100){options.addView(text("结果过多，请缩小筛选范围",13));break;}
                options.addView(button(label,()->{
                    Runnable apply=()->{r.manual=true;r.selected=p.id;dialog.dismiss();calculate(false);};
                    if(!BomMatch.matches(r,p,state.rules))new AlertDialog.Builder(this).setTitle("该物料不符合所选匹配条件")
                            .setMessage("只有确认阻值/容值、封装、颜色和电气规格均适合你的电路时，才应使用此替代料。")
                            .setNegativeButton("取消",null).setPositiveButton("确认指定",(d,w)->apply.run()).show();else apply.run();
                }));
            }
            if(shown==0)options.addView(text("没有库存匹配，可编辑 BOM 参数或查看全部库存",14));
        };
        filter.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){populate.run();}public void afterTextChanged(android.text.Editable e){}});
        dialog.show();populate.run();
    }
    private void exportMenu() {
        if(state.rows.isEmpty()||busy){toast("请先导入 BOM");return;}
        if(!calculate(false))return;
        if(state.rows.stream().anyMatch(r->!r.issue.isEmpty())){toast("存在数量无效的行，请先修改再导出");return;}
        new AlertDialog.Builder(this).setTitle("导出修改后的 BOM 和缺料结果").setItems(new String[]{"CSV","XLSX"},(d,i)->{
            busy=true;worker.execute(()->{
                try {
                    List<List<String>> rows=BomMatch.export(state.headers,state.rows,stock,state.sets);
                    byte[] bytes=i==0?BomFiles.writeCsv(rows):BomFiles.writeXlsx(rows);
                    runOnUiThread(()->{busy=false;pendingExport=bytes;export.launch("BOM匹配-"+state.sets+"套"+(i==0?".csv":".xlsx"));});
                }catch(Exception e){runOnUiThread(()->{busy=false;error(e);});}
            });
        }).show();
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xff19362f);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setOnClickListener(v->{if(!busy)action.run();});return b;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void error(Exception e){if(!isDestroyed() && !isFinishing())new AlertDialog.Builder(this).setTitle("BOM 操作未完成").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}

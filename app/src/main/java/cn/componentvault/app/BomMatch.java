package cn.componentvault.app;

import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;

/** BOM 试算仅分配可用数量，不扣库存；所有行共享同一份剩余量，避免重复占用。 */
public final class BomMatch {
    public static final String[] KEYS={"reference","quantity","category","value","code","model","footprint","color","voltage","power","current","description"};
    public static final String[] LABELS={"位号","每套数量","类别","阻值/容值/感值","物料编码","型号","封装","颜色","电压","功率","电流","描述"};
    public static final class Row implements java.io.Serializable {
        public List<String> original=new ArrayList<>();
        public String reference="",value="";
        public Part request=new Part();
        public long perSet=1,selected=-1,required,allocated,shortage;
        public boolean manual;
        public Map<Long,Long> allocations=new LinkedHashMap<>();
        public String issue="";
    }
    public static final class Rules implements java.io.Serializable {
        public final Set<String> fields=new LinkedHashSet<>(Arrays.asList("value","footprint"));
    }
    private BomMatch() {}
    public static int guess(String key,List<String> headers) {
        String[][] aliases={{"reference","reference designator","designator","designators","refdes","位号","元件位号"},
                {"quantity","qty","count","数量","用量","每套数量"},{"category","type","类别","分类","种类"},
                {"value","comment","参数","标称值","阻值","容值","感值","阻值/容值/感值"},{"code","lcsc","lcsc part #","lcsc part","supplier part","立创编号","商品编号","物料编码","物料编号"},
                {"model","mpn","manufacturer part number","型号","厂家型号"},{"footprint","package","封装","封装名称"},
                {"color","颜色","光色"},{"voltage","电压","耐压","额定电压"},{"power","功率","额定功率"},{"current","电流","额定电流"},{"description","描述","规格","规格描述"}};
        int index=Arrays.asList(KEYS).indexOf(key); if(index<0)return -1;
        for(int i=0;i<headers.size();i++) for(String alias:aliases[index]) if(norm(headers.get(i)).equals(norm(alias))) return i;
        return -1;
    }
    public static List<Row> rows(BomFiles.Sheet sheet,int header,Map<String,Integer> mapping) {
        List<Row> result=new ArrayList<>();
        for(int i=header+1;i<sheet.rows.size();i++) {
            List<String> data=sheet.rows.get(i); if(data.stream().allMatch(String::isEmpty)) continue;
            Row r=new Row(); r.original=new ArrayList<>(data); r.reference=get(data,mapping,"reference"); r.value=get(data,mapping,"value");
            Part p=LabelParser.parse(get(data,mapping,"description")+"\n"+r.value);
            p.code=get(data,mapping,"code"); p.model=get(data,mapping,"model");
            String category=get(data,mapping,"category"); if(!category.isEmpty())p.category=category;
            if(p.category.isEmpty() && !r.reference.isEmpty()) {
                String ref=r.reference.toUpperCase(Locale.ROOT);
                if(ref.matches("R\\d.*"))p.category="电阻"; else if(ref.matches("C\\d.*"))p.category="电容"; else if(ref.matches("L\\d.*"))p.category="电感";
            }
            p.resistance=PassiveValues.first(r.value,"resistance",p.category); p.capacitance=PassiveValues.first(r.value,"capacitance",p.category); p.inductance=PassiveValues.first(r.value,"inductance",p.category);
            // 位号/明确类型可消除 10K/4R7 的歧义；电容、电感省略单位时按常见 u/n/p 后缀解释。
            if(p.category.contains("电容") && p.capacitance.isEmpty() && r.value.matches("(?i)\\d+(?:[.]\\d+)?[unpµ]")) p.capacitance=PassiveValues.first(r.value+"F","capacitance",p.category);
            if(p.category.contains("电感") && p.inductance.isEmpty() && r.value.matches("(?i)\\d+(?:[.]\\d+)?[unpµ]")) p.inductance=PassiveValues.first(r.value+"H","inductance",p.category);
            p.footprint=get(data,mapping,"footprint"); p.color=get(data,mapping,"color");
            p.voltage=get(data,mapping,"voltage"); p.power=get(data,mapping,"power"); p.current=get(data,mapping,"current");
            r.request=p;
            String q=get(data,mapping,"quantity");
            try { r.perSet=quantity(q); }
            catch(Exception e) { r.perSet=0; r.issue="第 "+(i+1)+" 行数量无效，请编辑；未默认按 1 个处理"; }
            result.add(r);
        }
        return result;
    }
    public static long quantity(String text) {
        long n=new BigDecimal(text.trim()).longValueExact();
        if(n<0 || n>InventoryStore.MAX_QUANTITY) throw new IllegalArgumentException("数量超出范围"); return n;
    }
    private static String get(List<String> data,Map<String,Integer> map,String key) { int col=map.getOrDefault(key,-1); return col<0||col>=data.size()?"":data.get(col).trim(); }

    public static boolean matches(Row row,Part stock,Rules rules) {
        if(rules.fields.isEmpty())return false;
        Part p=row.request;
        for(String field:rules.fields) {
            String a="",b="";
            switch(field) {
                case "value":
                    boolean any=false;
                    for(String[] values:new String[][]{{p.resistance,stock.resistance},{p.capacitance,stock.capacitance},{p.inductance,stock.inductance}}) {
                        if(!values[0].isEmpty()) { any=true; if(!values[0].equals(values[1])) return false; }
                    }
                    if(!any)return false; continue;
                case "code":a=p.code;b=stock.code;break;
                case "model":a=p.model;b=stock.model;break;
                case "footprint":a=p.footprint;b=stock.footprint;break;
                case "color":a=p.color;b=stock.color;
                    String ca=ElectricalValues.color(a),cb=ElectricalValues.color(b); if(!ca.isEmpty())a=ca;if(!cb.isEmpty())b=cb;break;
                case "category":a=p.category;b=stock.category;break;
                case "voltage":a=p.voltage;b=stock.voltage;break;
                case "power":a=p.power;b=stock.power;break;
                case "current":a=p.current;b=stock.current;break;
            }
            if(a.isEmpty() || b.isEmpty())return false;
            if(Arrays.asList("voltage","power","current").contains(field)) {
                a=ElectricalValues.first(a,field); b=ElectricalValues.first(b,field);
                if(a.isEmpty() || b.isEmpty()) return false;
            }
            if(!norm(a).equals(norm(b))) return false;
        }
        return true;
    }
    public static void calculate(List<Row> rows,List<Part> stocks,Rules rules,long sets,boolean rematch) {
        if(sets<1 || sets>1000000)throw new IllegalArgumentException("套数需为 1 至 1000000");
        Map<Long,Long> remaining=new HashMap<>(); for(Part p:stocks)remaining.put(p.id,p.quantity);
        for(Row row:rows) {
            if(row.perSet<0 || row.perSet>InventoryStore.MAX_QUANTITY)throw new IllegalArgumentException("每套数量无效");
            row.required=Math.multiplyExact(row.perSet,sets); row.allocated=0; row.shortage=row.required;
            row.allocations.clear();
            if(!row.manual)row.selected=-1;
            if(!row.issue.isEmpty())continue;
            if(row.selected<0 && !row.manual) {
                List<Part> compatible=new ArrayList<>();for(Part p:stocks)if(matches(row,p,rules))compatible.add(p);
                compatible.sort((a,b)->Long.compare(remaining.get(b.id),remaining.get(a.id)));
                for(Part p:compatible) {
                    if(row.selected<0)row.selected=p.id;
                    long n=Math.min(row.required-row.allocated,remaining.get(p.id));
                    if(n>0) { row.allocations.put(p.id,n);row.allocated+=n;remaining.put(p.id,remaining.get(p.id)-n); }
                    if(row.allocated==row.required)break;
                }
                row.shortage=row.required-row.allocated;continue;
            }
            if(row.selected>=0 && remaining.containsKey(row.selected)) {
                row.allocated=Math.min(row.required,remaining.get(row.selected));
                if(row.allocated>0)row.allocations.put(row.selected,row.allocated);
                remaining.put(row.selected,remaining.get(row.selected)-row.allocated); row.shortage=row.required-row.allocated;
            }
        }
    }
    public static List<List<String>> export(List<String> headers,List<Row> rows,List<Part> stocks,long sets) {
        List<List<String>> data=new ArrayList<>(); List<String> head=new ArrayList<>(headers);
        head.addAll(Arrays.asList("修改位号","修改值","修改类别","修改封装","修改颜色","修改每套数量","修改物料编码","修改型号","修改电压","修改功率","修改电流","套数","总需求","库存匹配状态","匹配物料编码","匹配型号","匹配参数","位置","本次可分配","缺料数量","备注/匹配方式")); data.add(head);
        Map<Long,Part> byId=new HashMap<>(); for(Part p:stocks)byId.put(p.id,p);
        for(Row r:rows) {
            List<String> row=new ArrayList<>(r.original); while(row.size()<headers.size())row.add("");
            if(row.size()>headers.size())row=new ArrayList<>(row.subList(0,headers.size()));
            Part p=byId.get(r.selected);
            row.addAll(Arrays.asList(r.reference,r.value,r.request.category,r.request.footprint,r.request.color,Long.toString(r.perSet),r.request.code,r.request.model,r.request.voltage,r.request.power,r.request.current,Long.toString(sets),Long.toString(r.required),
                    !r.issue.isEmpty()?"需修正":p==null?"无匹配":r.shortage>0?"库存不足":"库存满足",p==null?"":p.code,p==null?"":p.model,p==null?"":PartDisplay.primary(p),p==null?"":p.location,
                    Long.toString(r.allocated),Long.toString(r.shortage),!r.issue.isEmpty()?r.issue:(r.manual?"用户指定，需自行确认兼容性":"按所选条件匹配，需确认电气兼容性")+"；"+allocationText(r,stocks))); data.add(row);
        }
        return data;
    }
    public static String norm(String s) { return PassiveValues.normalize(s).replaceAll("\\s+","").toUpperCase(Locale.ROOT); }
    public static String allocationText(Row r,List<Part> stock) {
        List<String> parts=new ArrayList<>();for(Part p:stock)if(r.allocations.containsKey(p.id))parts.add((p.code.isEmpty()?p.model:p.code)+" @"+p.location+" ×"+r.allocations.get(p.id));
        return String.join("; ",parts);
    }
}

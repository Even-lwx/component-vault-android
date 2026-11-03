package cn.componentvault.app;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.*;
import java.util.regex.*;

/** 首页查询、排序和种数统计；同一型号分放多个库位时只算一种物料。 */
public final class InventoryBrowser {
    public static final String[] SORT_NAMES={"最新录入","库存从少到多","库存从多到少","类别名称","参数从小到大","型号名称"};
    public static final String[] CATEGORY_SORT_NAMES={"种数优先","名称排序","最近访问"};
    private InventoryBrowser() {}
    public static final class Filter {
        public String query="",category="全部",footprint="",color="",location="";
        public int stock=0,sort=0;
        public long lowStock=10;
        public boolean active() { return !query.trim().isEmpty()||!category.equals("全部")||!footprint.isEmpty()||!color.isEmpty()||!location.isEmpty()||stock!=0; }
        public int extraCount() {return (footprint.isEmpty()?0:1)+(color.isEmpty()?0:1)+(location.isEmpty()?0:1)+(stock==0?0:1);}
    }
    public static String kind(Part p) {
        if(!p.code.trim().isEmpty())return "code:"+p.code.trim().toUpperCase(Locale.ROOT);
        String[] fields={p.model,p.brand,p.footprint,p.resistance,p.capacitance,p.inductance,p.color,p.voltage,p.power,p.current};
        StringBuilder key=new StringBuilder("part:");
        for(String field:fields)key.append(field.length()).append(':').append(field).append(';');
        return key.toString();
    }
    public static int kinds(List<Part> parts) {Set<String> keys=new HashSet<>();for(Part p:parts)keys.add(kind(p));return keys.size();}
    public static Map<String,Integer> categoryCounts(List<Part> parts) {
        Map<String,Set<String>> groups=new HashMap<>();
        for(Part p:parts)groups.computeIfAbsent(p.category,k->new HashSet<>()).add(kind(p));
        Map<String,Integer> counts=new HashMap<>();for(var e:groups.entrySet())counts.put(e.getKey(),e.getValue().size());return counts;
    }
    public static List<String> categories(List<String> names,Map<String,Integer> counts,Set<String> pinned,Map<String,Long> visits,int order,boolean hideEmpty,String selected) {
        List<String> result=new ArrayList<>();
        for(String name:names)if(!hideEmpty||counts.getOrDefault(name,0)>0||pinned.contains(name)||name.equals(selected))result.add(name);
        Collator collator=Collator.getInstance(Locale.CHINA);
        result.sort((a,b)->{
            int compare=Boolean.compare(pinned.contains(b),pinned.contains(a));if(compare!=0)return compare;
            if(order==2){compare=Long.compare(visits.getOrDefault(b,0L),visits.getOrDefault(a,0L));if(compare!=0)return compare;}
            if(order==0){compare=Integer.compare(counts.getOrDefault(b,0),counts.getOrDefault(a,0));if(compare!=0)return compare;}
            return collator.compare(a,b);
        });return result;
    }
    public static List<Part> apply(List<Part> parts,Filter filter) {
        List<Part> out=new ArrayList<>();
        for(Part p:parts) {
            if(!filter.category.equals("全部")&&!p.category.equals(filter.category))continue;
            if(!filter.footprint.isEmpty()&&!p.footprint.equals(filter.footprint))continue;
            if(!filter.color.isEmpty()&&!p.color.equals(filter.color))continue;
            if(!filter.location.isEmpty()&&!p.location.equals(filter.location))continue;
            if(filter.stock==1&&p.quantity<=0||filter.stock==2&&p.quantity!=0||filter.stock==3&&(p.quantity<=0||p.quantity>filter.lowStock))continue;
            boolean matched=true;for(String word:filter.query.trim().split("\\s+"))if(!word.isEmpty()&&!matches(p,word)){matched=false;break;}
            if(matched)out.add(p);
        }
        Collator collator=Collator.getInstance(Locale.CHINA);
        out.sort((a,b)->{
            int cmp=0;
            switch(filter.sort){
                case 1:cmp=Long.compare(a.quantity,b.quantity);break;
                case 2:cmp=Long.compare(b.quantity,a.quantity);break;
                case 3:cmp=collator.compare(a.category,b.category);break;
                case 4:cmp=compareValues(a,b);break;
                case 5:cmp=collator.compare(a.model,b.model);break;
            }
            return cmp!=0?cmp:Long.compare(b.id,a.id);
        });return out;
    }
    private static boolean matches(Part p,String word) {
        String[] values={p.resistance,p.capacitance,p.inductance};
        String[] kinds={"resistance","capacitance","inductance"};
        for(int i=0;i<kinds.length;i++) {
            String parsed=PassiveValues.first(word,kinds[i],"");
            if(!parsed.isEmpty())return parsed.equals(values[i]);
        }
        String text=String.join(" ",p.category,p.model,p.code,p.brand,p.footprint,p.color,p.location,p.description,p.note,p.platform,p.resistance,p.capacitance,p.inductance,p.voltage,p.power,p.current);
        return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT).contains(PassiveValues.normalize(word).replaceAll("\\s+","").toLowerCase(Locale.ROOT));
    }
    private static int compareValues(Part a,Part b) {
        String[] av={a.resistance,a.capacitance,a.inductance},bv={b.resistance,b.capacitance,b.inductance};
        int ai=3,bi=3;for(int i=0;i<3;i++){if(ai==3&&!av[i].isEmpty())ai=i;if(bi==3&&!bv[i].isEmpty())bi=i;}
        if(ai!=bi)return Integer.compare(ai,bi);if(ai==3)return 0;
        BigDecimal aa=number(av[ai]),bb=number(bv[bi]);
        if(aa==null||bb==null)return aa==null?(bb==null?0:1):-1;
        return aa.compareTo(bb);
    }
    private static BigDecimal number(String value) {
        Matcher m=Pattern.compile("([0-9]+(?:[.][0-9]+)?)\\s*([GMkKmunpµμ]?)[ΩFH]").matcher(value);
        if(!m.matches())return null;
        int exponent;switch(m.group(2)) {case "G":exponent=9;break;case "M":exponent=6;break;case "k":case "K":exponent=3;break;case "m":exponent=-3;break;case "u":case "µ":case "μ":exponent=-6;break;case "n":exponent=-9;break;case "p":exponent=-12;break;default:exponent=0;}
        return new BigDecimal(m.group(1)).scaleByPowerOfTen(exponent);
    }
    public static List<String> choices(List<Part> parts,String field,String category) {
        TreeSet<String> set=new TreeSet<>(Collator.getInstance(Locale.CHINA));
        for(Part p:parts){if(!category.equals("全部")&&!category.equals(p.category))continue;String value=field.equals("footprint")?p.footprint:field.equals("color")?p.color:p.location;if(!value.isEmpty())set.add(value);}
        return new ArrayList<>(set);
    }
}

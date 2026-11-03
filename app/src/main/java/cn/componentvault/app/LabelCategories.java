package cn.componentvault.app;

import java.util.*;
import java.util.regex.Pattern;

/** 优先使用标签类别原文；省略号只按唯一前缀补全，不推测有歧义的名称。 */
public final class LabelCategories {
    public static final String LED="发光二极管/LED";
    public static final String FAST_DIODE="快恢复/高效率二极管";
    private static final List<String> STANDARD=Collections.unmodifiableList(Arrays.asList(
            "电阻","贴片电阻","厚膜电阻","薄膜电阻","电容","贴片电容","铝电解电容","钽电容",
            "电感","功率电感","二极管",LED,FAST_DIODE,"肖特基二极管","稳压二极管","开关二极管","整流二极管",
            "集成电路","晶体管","MOSFET","晶振","保险丝","一次性保险丝","自恢复保险丝","连接器","开关","传感器","电源模块","磁珠","保护器件","其他"));
    private LabelCategories() {}
    public static List<String> standard() { return STANDARD; }

    public static String canonical(String value) {
        String clean=clean(value).replace("快恢復","快恢复").replace("發光二極管","发光二极管").replace("二極","二极");
        if(clean.equalsIgnoreCase("LED") || clean.equals("发光二极管")) return LED;
        if(clean.equals("快恢复二极管") || clean.equals("高效率二极管")
                || clean.equals("快恢復/高效率二極管")) return FAST_DIODE;
        if(clean.equals("可恢复保险丝")) return "自恢复保险丝";
        return PassiveValues.normalize(value==null?"":value).replace("快恢復","快恢复").replace("發光二極管","发光二极管").replace("二極","二极")
                .replace('／','/').replace('｜','/').replace('|','/').trim();
    }

    public static String complete(String value,List<String> custom) {
        String clean=canonical(value);
        if(!truncated(clean)) return clean;
        String prefix=clean(clean).replaceFirst("(?:\\.{2,}|…+|‥+|⋯+).*$", "");
        if(prefix.length()<2) return clean;
        LinkedHashSet<String> matches=new LinkedHashSet<>();
        for(String name:dictionary(custom)) if(clean(name).startsWith(prefix)) matches.add(name);
        return matches.size()==1?matches.iterator().next():clean;
    }

    public static String fromLabel(String text,List<String> custom) {
        String best=""; int score=0;
        for(String line:text.split("\\n")) {
            String value=clean(line).replaceFirst("(?i)^(?:分类|类别|物料类别|category)[:：]", "");
            if(truncated(value)) {
                String full=complete(value,custom);
                if(!truncated(full) && full.length()+100>score) { best=full; score=full.length()+100; }
                else {
                    String prefix=value.replaceFirst("(?:\\.{2,}|…+|‥+|⋯+).*$", "");
                    if(prefix.length()>=2 && score<99 && dictionary(custom).stream().anyMatch(name->clean(name).startsWith(prefix))) { best=value; score=99; }
                }
                continue;
            }
            String alias=canonical(value);
            for(String name:dictionary(custom)) {
                boolean exact=clean(alias).equalsIgnoreCase(clean(name));
                boolean contained=value.contains(clean(name)) && name.length()>=3;
                if(exact || contained) {
                    int rank=name.length()+(exact?100:0);
                    if(rank>score) { best=name; score=rank; }
                }
            }
            // 供应商常用简称也是确定的类别别名。
            if((value.equals("发光二极管") || value.equalsIgnoreCase("LED")) && score<100+LED.length()) { best=LED; score=100+LED.length(); }
            if(value.contains("发光二极管") && score<LED.length()) { best=LED; score=LED.length(); }
            if(value.contains("可恢复保险丝") && score<"自恢复保险丝".length()) { best="自恢复保险丝"; score=best.length(); }
        }
        return best;
    }

    public static boolean truncated(String text) { return Pattern.compile("\\.{2,}|…|‥|⋯").matcher(text).find(); }
    public static boolean moreSpecific(String detail,String broad) {
        return !detail.equals(broad) && (broad.equals("二极管") && detail.contains("二极管")
                || broad.equals("电阻") && detail.contains("电阻")
                || broad.equals("电容") && detail.contains("电容")
                || broad.equals("电感") && detail.contains("电感")
                || broad.equals("保险丝") && detail.contains("保险丝"));
    }
    private static String clean(String value) { return PassiveValues.normalize(value==null?"":value).replace('／','/').replace('｜','/').replace('|','/').replaceAll("\\s+", ""); }
    private static Set<String> dictionary(List<String> custom) {
        LinkedHashSet<String> names=new LinkedHashSet<>(STANDARD);
        for(String name:custom) if(!truncated(name) && !name.trim().isEmpty()) names.add(canonical(name));
        return names;
    }
}

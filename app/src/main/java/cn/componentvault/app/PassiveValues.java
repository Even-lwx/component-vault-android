package cn.componentvault.app;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** 从明确单位或字段上下文解析 R/C/L，不从料号和裸数字猜测物理量。 */
public final class PassiveValues {
    private static final String NUMBER="(?:\\d+(?:[.,]\\d+)?|[.]\\d+)";
    private static final String LEFT="(?<![A-Za-z0-9_./-])";
    private static final String RIGHT="(?![A-Za-z0-9_])";
    private PassiveValues() {}

    public static List<String> candidates(String input,String kind,String category) {
        String text=normalize(input);
        LinkedHashSet<String> result=new LinkedHashSet<>();
        String units;
        if(kind.equals("resistance")) units="(?:[GMKkRmµun]?[ \\t]*[ΩΩ]|[GMKkmµun]?[ \\t]*(?i:ohms?)|兆欧姆?|千欧姆?|毫欧姆?|微欧姆?|欧姆?)";
        else if(kind.equals("capacitance")) units="(?:[pPnNuUµμmM]?[ \\t]*[fF]|皮法(?:拉)?|纳法(?:拉)?|微法(?:拉)?|毫法(?:拉)?|法拉)";
        else if(kind.equals("inductance")) units="(?:[pPnNuUµμmM]?[ \\t]*[hH]|皮亨(?:利)?|纳亨(?:利)?|微亨(?:利)?|毫亨(?:利)?|亨利)";
        else return new ArrayList<>();
        Matcher explicit=Pattern.compile(LEFT+"("+NUMBER+")[ \\t]*("+units+")"+RIGHT).matcher(text);
        while(explicit.find()) add(result,explicit.group(1),explicit.group(2).replaceAll("[ \\t]", ""),kind);

        // 4K7、2R2、R22、4u7：仅在字段/分类已确认其物理量时解释。
        if(context(text,kind,category)) {
            String markers=kind.equals("resistance")?"RrKkMm":kind.equals("capacitance")?"pPnNuUµm":"pPnNuUµmRr";
            Matcher embedded=Pattern.compile(LEFT+"(\\d*)(["+markers+"])(\\d+)"+RIGHT).matcher(text);
            while(embedded.find()) {
                String mark=embedded.group(2);
                String numeric=(embedded.group(1).isEmpty()?"0":embedded.group(1))+"."+embedded.group(3);
                String unit=kind.equals("resistance")?(mark.equalsIgnoreCase("r")?"Ω":mark+"Ω"):
                        kind.equals("inductance") && mark.equalsIgnoreCase("r")?"µH":mark+(kind.equals("capacitance")?"F":"H");
                add(result,numeric,unit,kind);
            }
            // “阻值:10K” 中 K 指千欧，普通型号或不带上下文的 10K 不作解释。
            if(kind.equals("resistance")) {
                Matcher shortValue=Pattern.compile(LEFT+"("+NUMBER+")[ \\t]*([RrKkM])"+RIGHT).matcher(text);
                while(shortValue.find()) add(result,shortValue.group(1),shortValue.group(2).equalsIgnoreCase("r")?"Ω":shortValue.group(2)+"Ω",kind);
            }
        }
        return new ArrayList<>(result);
    }

    public static String first(String text,String kind,String category) {
        List<String> values=candidates(text,kind,category);
        return values.isEmpty()?"":values.get(0);
    }

    public static String normalize(String text) {
        if(text==null) return "";
        StringBuilder b=new StringBuilder();
        for(char c:text.toCharArray()) b.append(c>='！' && c<='～'?(char)(c-0xfee0):c);
        return b.toString().replace('μ','µ').replace('Ω','Ω').replace('：',':').replace('．','.')
                .replaceAll("(?<=\\d)[ \\t]*\\.[ \\t]*(?=\\d)",".");
    }

    private static boolean context(String text,String kind,String category) {
        if(kind.equals("resistance")) return category.contains("电阻") || Pattern.compile("(?i)阻值|电阻|resistance|resistor").matcher(text).find();
        if(kind.equals("capacitance")) return category.contains("电容") || Pattern.compile("(?i)容值|电容|capacitance|capacitor").matcher(text).find();
        return category.contains("电感") || Pattern.compile("(?i)感值|电感|inductance|inductor").matcher(text).find();
    }

    private static void add(Set<String> values,String number,String unit,String kind) {
        try {
            BigDecimal base=new BigDecimal(number.replace(',','.')).scaleByPowerOfTen(power(unit));
            if(base.signum()<0) return;
            values.add(format(base,kind));
        } catch(NumberFormatException ignored) { }
    }

    private static int power(String unit) {
        if(unit.contains("兆")) return 6;
        if(unit.contains("千")) return 3;
        if(unit.contains("毫")) return -3;
        if(unit.contains("微")) return -6;
        if(unit.contains("纳")) return -9;
        if(unit.contains("皮")) return -12;
        if(unit.startsWith("G")) return 9;
        if(unit.startsWith("M")) return 6;
        if(unit.startsWith("k") || unit.startsWith("K")) return 3;
        if(unit.startsWith("m")) return -3;
        if(unit.startsWith("u") || unit.startsWith("U") || unit.startsWith("µ")) return -6;
        if(unit.startsWith("n") || unit.startsWith("N")) return -9;
        if(unit.startsWith("p") || unit.startsWith("P")) return -12;
        return 0;
    }

    private static String format(BigDecimal base,String kind) {
        int[] powers=kind.equals("resistance")?new int[]{9,6,3,0,-3,-6,-9}:new int[]{6,3,0,-3,-6,-9,-12};
        int selected=0;
        if(base.signum()>0) {
            selected=powers[powers.length-1];
            for(int power:powers) if(base.compareTo(BigDecimal.ONE.scaleByPowerOfTen(power))>=0) { selected=power; break; }
        }
        String prefix;
        switch(selected) {
            case 9: prefix="G"; break;
            case 6: prefix="M"; break;
            case 3: prefix="k"; break;
            case -3: prefix="m"; break;
            case -6: prefix="µ"; break;
            case -9: prefix="n"; break;
            case -12: prefix="p"; break;
            default: prefix="";
        }
        String suffix=kind.equals("resistance")?"Ω":kind.equals("capacitance")?"F":"H";
        return base.scaleByPowerOfTen(-selected).stripTrailingZeros().toPlainString()+" "+prefix+suffix;
    }
}

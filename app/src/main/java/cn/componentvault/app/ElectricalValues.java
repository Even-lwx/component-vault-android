package cn.componentvault.app;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.*;

/** 仅从明确单位或电气参数标签解析额定值，不从型号中的数字推导规格。 */
public final class ElectricalValues {
    private static final String NUMBER="(?:\\d+(?:[.,]\\d+)?|[.]\\d+)";
    private static final String[][] COLORS={{"暖白","暖白色"},{"warm white","暖白色"},{"冷白","冷白色"},{"cool white","冷白色"},{"红绿蓝","RGB"},{"rgb","RGB"},
            {"红色","红色"},{"分光红","红色"},{"red","红色"},{"红光","红色"},{"绿色","绿色"},{"分光绿","绿色"},{"green","绿色"},{"绿光","绿色"},
            {"蓝色","蓝色"},{"分光蓝","蓝色"},{"blue","蓝色"},{"蓝光","蓝色"},{"黄色","黄色"},{"yellow","黄色"},{"琥珀","琥珀色"},{"amber","琥珀色"},
            {"橙色","橙色"},{"orange","橙色"},{"白色","白色"},{"white","白色"},{"白光","白色"},{"紫色","紫色"},{"purple","紫色"},{"粉色","粉色"},{"pink","粉色"}};
    private ElectricalValues() {}

    public static String first(String input,String kind) {
        String text=PassiveValues.normalize(input).replace("\u00a0"," ");
        if(kind.equals("power")) {
            Matcher fraction=Pattern.compile("(?<![A-Za-z0-9./])([0-9]+)\\s*/\\s*([0-9]+)\\s*[wW](?![A-Za-z0-9])").matcher(text);
            if(fraction.find()) {
                BigDecimal denominator=new BigDecimal(fraction.group(2));
                if(denominator.signum()>0) return new BigDecimal(fraction.group(1)).divide(denominator,java.math.MathContext.DECIMAL64).stripTrailingZeros().toPlainString()+" W";
            }
        }
        String unit,context;
        switch(kind) {
            case "voltage": unit="(?:k[ \\t]*V|m[ \\t]*V|V|千伏|毫伏|伏(?:特)?)"; context="(?:耐压|额定电压|电压|反向电压|voltage|volt)"; break;
            case "power": unit="(?:m[ \\t]*W|W|毫瓦|瓦(?:特)?)"; context="(?:额定功率|功率|power)"; break;
            case "current": unit="(?:u[ \\t]*A|µ[ \\t]*A|m[ \\t]*A|A|微安|毫安|安(?:培)?)"; context="(?:额定电流|工作电流|电流|电流容量|current|amp(?:ere)?)"; break;
            default: return "";
        }
        Pattern labeled=Pattern.compile("(?i)"+context+"[ \\t]*[:=：]?[ \\t]*("+NUMBER+"[ \\t]*"+unit+")(?![A-Za-z0-9])");
        Matcher m=labeled.matcher(text);
        if(m.find()) return canonical(m.group(1),kind);
        Pattern explicit=Pattern.compile("(?i)(?<![A-Za-z0-9_./-])("+NUMBER+"[ \\t]*"+unit+")(?![A-Za-z0-9])");
        m=explicit.matcher(text);
        return m.find()?canonical(m.group(1),kind):"";
    }

    public static String color(String input) {
        String text=PassiveValues.normalize(input).toLowerCase(Locale.ROOT);
        String labeled="(?i)(?:发光颜色|颜色|光色|color)[ \\t]*[:=：]?[ \\t]*([^\\n,，;；]{1,24})";
        Matcher m=Pattern.compile(labeled).matcher(text);
        if(m.find()) {
            String value=m.group(1).trim().replaceAll("[()（）]","");
            String known=knownColor(value);
            if(!known.isEmpty()) return known;
        }
        return knownColor(text);
    }

    private static String knownColor(String value) {
        for(String[] alias:COLORS) {
            if(alias[0].matches("[a-z ]+")) {
                if(Pattern.compile("(?i)(?<![a-z0-9])"+Pattern.quote(alias[0])+"(?![a-z0-9])").matcher(value).find()) return alias[1];
            } else if(value.contains(alias[0])) return alias[1];
        }
        return "";
    }

    private static String canonical(String raw,String kind) {
        String value=raw.replaceAll("\\s+","").replace(',','.');
        value=value.replace("千伏","kV").replace("毫伏","mV").replace("伏特","V").replace("伏","V")
                .replace("毫瓦","mW").replace("瓦特","W").replace("瓦","W")
                .replace("微安","µA").replace("毫安","mA").replace("安培","A").replace("安","A");
        Matcher m=Pattern.compile("(?i)("+NUMBER+")(kV|mV|V|mW|W|uA|µA|mA|A)").matcher(value);
        if(!m.matches()) return "";
        String number=m.group(1),unit=m.group(2);
        String prefix=unit.length()>1?unit.substring(0,unit.length()-1):"";
        String base=unit.substring(unit.length()-1).toUpperCase(Locale.ROOT);
        if(kind.equals("voltage")) base="V";
        else if(kind.equals("power")) base="W";
        else base="A";
        String prefixOut;
        if(prefix.equalsIgnoreCase("k")) prefixOut="k";
        else if(prefix.equalsIgnoreCase("m")) prefixOut="m";
        else if(prefix.equalsIgnoreCase("u") || prefix.equals("µ")) prefixOut="µ";
        else prefixOut="";
        try { number=new BigDecimal(number).stripTrailingZeros().toPlainString(); }
        catch(NumberFormatException ignored) { return ""; }
        return number+" "+prefixOut+base;
    }
}

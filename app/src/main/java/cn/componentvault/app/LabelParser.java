package cn.componentvault.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 标签只是候选信息，必须经用户核对后才能计入库存。 */
public final class LabelParser {
    private static final String PACKAGE = "(?i)(?<![A-Z0-9])(?:DO\\s*-?\\s*214\\s*[A-Z]{0,2}(?:\\s*\\([A-Z]+\\))?|(?:TSSOP|SSOP|SOP|SOIC|SOT|QFN|DFN|LQFP|DIP)\\s*-?\\s*\\d+(?:\\s*-\\s*\\d+)?|0201|0402|0603|0805|1206|1210|2010|2512|SMA|SMB|SMC)(?![A-Z0-9])";
    private LabelParser() {}

    public static Part parse(String input) {
        return parse(input,java.util.Collections.emptyList());
    }

    public static Part parse(String input,java.util.List<String> categories) {
        Part p = new Part();
        String original=input==null?"":input;
        // 聚合结果的原文只供查阅，不能重新污染已票选出的字段。
        String structured=original.split("\\n--- 字段候选 ---",2)[0];
        String text = PassiveValues.normalize(structured).replace('\r', '\n')
                .replace('（', '(').replace('）', ')').replace('－', '-').replace('—', '-')
                // 标签 OCR 会在括号、短横线和冒号两侧插入空格，先做格式归一化再提取字段。
                .replaceAll("[ \\t]*([():,/\\-])[ \\t]*", "$1");
        p.raw = original;
        p.code = first(text, "(?i)(?<![A-Z0-9])C\\s*\\d{4,12}(?![A-Z0-9])").replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        p.platform = field(text, "platform|平台|采购平台|来源平台|supplier|供应商");
        p.model = compact(field(text, "model|型号|mpn|partNumber|part_number"));
        p.brand = field(text, "brand|品牌|manufacturer|制造商");
        p.footprint = field(text, "package|封装|footprint");
        p.category = field(text, "category|分类|类别|物料类别");
        if(!p.category.isEmpty()) p.category=LabelCategories.complete(p.category,categories);
        else p.category=LabelCategories.fromLabel(text,categories);
        p.description = field(text, "description|描述|规格");
        if (p.platform.isEmpty()) {
            if (contains(text, "立创商城|嘉立创商城|LCSC|(?:szlcsc|lcsc)\\.com")) p.platform = "立创商城";
            else if (contains(text, "Digi[ -]?Key|得捷|digikey\\.(?:cn|com)")) p.platform = "DigiKey";
            else if (contains(text, "Mouser|贸泽")) p.platform = "Mouser";
            else if (contains(text, "1688|阿里巴巴")) p.platform = "1688";
            else if (contains(text, "淘宝|taobao\\.com")) p.platform = "淘宝";
        }
        String explicitCode = field(text, "code|物料编码|lcsc|productCode");
        if (!explicitCode.isEmpty()) p.code = explicitCode;
        String qty = capture(text, "(?i)(?:QTY|数量|quantity)[\\s\"']*[:=]?[\\s\"']*(\\d+)");
        if (!qty.isEmpty()) {
            try { p.quantity = Long.parseLong(qty); } catch (NumberFormatException ignored) { }
        }
        if (p.footprint.isEmpty()) p.footprint = first(text, PACKAGE);
        p.footprint = compact(p.footprint).toUpperCase(java.util.Locale.ROOT);
        for (String line : text.split("\\n")) {
            String s = line.trim().replaceAll("\\s*([-()/])\\s*", "$1");
            if (p.brand.isEmpty() && s.matches("[A-Za-z][A-Za-z0-9 ._-]*[（(][\\p{IsHan}A-Za-z0-9 ]*[\\p{IsHan}][\\p{IsHan}A-Za-z0-9 ]*[）)]")) {
                // 封装名称如 DO-214AC(SMA) 不能当作品牌。
                if (!s.matches("(?i)^(DO-|SOT|SOP|QFN|DIP).*")) p.brand = s;
            }
            if (p.brand.isEmpty() && contains(s, "^(?:KEXIN|XINGLIGHT|YAGEO|WALSIN|MURATA|SAMSUNG|VISHAY|NEXPERIA|TEXAS INSTRUMENTS|STMicroelectronics|ON SEMICONDUCTOR)$")) p.brand = s;
            if (p.model.isEmpty() && s.matches("[A-Za-z0-9][A-Za-z0-9_./() -]{2,79}")
                    && s.matches(".*\\d.*") && s.matches(".*[A-Za-z].*")
                    && !s.matches("(?i)C\\s*\\d+") && !s.matches(PACKAGE)
                    && !s.matches("(?i)^(SO\\d|QTY|ROHS|数量|LOT|DATE|SKU|ORDER).*")) {
                if (!s.matches("\\d+-[A-Z]-.*") && !s.matches("(?i)\\d+(?:NS|US|MS|V|KV|A|MA|W|UF|NF|PF)")) p.model = compact(s);
            }
            if (p.description.isEmpty() && (s.contains("耐压") || s.contains("电流") || s.contains("贴片") || s.contains("电阻") || s.contains("电容"))) p.description = s;
        }
        if (p.category.isEmpty()) {
            if (contains(text,"自恢复保险丝|可恢复保险丝|resettable|polyfuse|PTC fuse")) p.category="自恢复保险丝";
            else if (contains(text,"一次性保险丝|一次性熔断器|non.?resettable")) p.category="一次性保险丝";
            else if (contains(text,"保险丝|熔断器|\\bfuse\\b")) p.category="保险丝";
            else if (text.matches("(?s).*(发光二极管|LED).*")) p.category = LabelCategories.LED;
            else if (text.contains("二极管") || p.model.matches("(?i)^(FR|SS|1N|RS)\\d.*")) p.category = "二极管";
            else if (text.contains("电阻")) p.category = "电阻";
            else if (text.contains("电容")) p.category = "电容";
            else if (text.contains("电感")) p.category = "电感";
        }
        p.resistance=PassiveValues.first(text,"resistance",p.category);
        p.capacitance=PassiveValues.first(text,"capacitance",p.category);
        p.inductance=PassiveValues.first(text,"inductance",p.category);
        p.color=field(text,"color|发光颜色|颜色|光色");
        if(p.color.isEmpty()) p.color=ElectricalValues.color(text);
        p.voltage=ElectricalValues.first(text,"voltage");
        p.power=ElectricalValues.first(text,"power");
        p.current=ElectricalValues.first(text,"current");
        if(p.category.isEmpty()) {
            if(!p.resistance.isEmpty() && p.capacitance.isEmpty() && p.inductance.isEmpty()) p.category="电阻";
            else if(!p.capacitance.isEmpty() && p.resistance.isEmpty() && p.inductance.isEmpty()) p.category="电容";
            else if(!p.inductance.isEmpty() && p.resistance.isEmpty() && p.capacitance.isEmpty()) p.category="电感";
        }
        if(!p.model.isEmpty() && (p.model.matches("(?i)[0-9.,]+[pkmunµ]?[fh]") || p.model.matches("[0-9.,]+[kKMm]?[ΩΩ]"))) p.model="";
        return p;
    }

    private static String field(String text, String names) {
        String value = capture(text, "(?im)(?:^|[\\n{,;&]|\\s)\\s*[\"']?(?:" + names + ")[\"']?\\s*[:=]\\s*[\"']?([^\\n\"',;}&]+)");
        // OCR 经常把相邻字段合并成一行，不能将后续字段吞入型号或品牌。
        return value.split("(?i)\\s+(?:平台|采购平台|供应商|型号|封装|品牌|数量|规格|颜色|光色|耐压|电压|功率|电流|model|package|brand|platform|qty|color|voltage|power|current)\\s*[:=]", 2)[0].trim();
    }

    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static boolean contains(String text, String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find(); }

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group().trim() : "";
    }

    private static String capture(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}

package cn.componentvault.app;

import java.util.*;
import java.util.regex.*;

/** 适配立创物料袋实际的非标准 JSON：pc=商品编号，pm=型号，qty=包装数量。 */
public final class QrPayload {
    private QrPayload() {}
    public static String uniqueProductPayload(List<String> payloads) {
        String selected=null,code="";
        for(String raw:payloads) {
            Part p=parse(raw,Collections.emptyList());
            if(!LcscProductParser.validCode(p.code)) continue;
            if(!code.isEmpty()&&!code.equals(p.code))return null;
            if(selected==null){selected=raw;code=p.code;}
        }
        return selected;
    }
    public static Part parse(String raw,List<String> categories) {
        String text=raw==null?"":raw.trim();
        String code=value(text,"pc"), model=value(text,"pm"), qty=value(text,"qty");
        StringBuilder normalized=new StringBuilder();
        if(code.matches("(?i)C\\d{4,12}")) normalized.append("code: ").append(code.toUpperCase(Locale.ROOT)).append('\n');
        if(!model.isEmpty()) normalized.append("model: ").append(model).append('\n');
        if(qty.matches("\\d{1,9}")) normalized.append("quantity: ").append(qty).append('\n');
        // 避免把订单号 on、供应商内部标识和商品包装数当成电气参数。
        Part p=LabelParser.parse(normalized.length()>0?normalized.toString():text,categories);
        p.raw=text;
        return p;
    }
    private static String value(String text,String key) {
        Matcher m=Pattern.compile("(?i)(?:^|[{,;])\\s*[\"']?"+Pattern.quote(key)+"[\"']?\\s*[:=]\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,;}]*))").matcher(text);
        if(!m.find()) return "";
        return (m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3)).trim();
    }
}

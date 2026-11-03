package cn.componentvault.app;

import java.util.*;

/** 只读编号一致的商品详情与参数表，不从推荐商品和购物车库存中提取参数。 */
public final class LcscProductParser {
    private LcscProductParser() {}
    public static boolean validCode(String code) { return code!=null && code.matches("C\\d{4,12}"); }

    public static Map<String,String> fields(Part p) {
        LinkedHashMap<String,String> values=new LinkedHashMap<>();
        String[] names={"code","model","platform","brand","footprint","category","resistance","capacitance","inductance","color","voltage","power","current"};
        String[] data={p.code,p.model,p.platform,p.brand,p.footprint,p.category,p.resistance,p.capacitance,p.inductance,p.color,p.voltage,p.power,p.current};
        for(int i=0;i<names.length;i++) if(!data[i].isEmpty()) values.put(names[i],data[i]);
        return values;
    }

    public static Part parse(String text,String expectedCode) {
        if(!validCode(expectedCode)) throw new IllegalArgumentException("请输入完整立创编号，例如 C489136");
        String normalized=text.replace("\u200b","").replace('\t','\n').replace('\r','\n');
        List<String> lines=new ArrayList<>();
        for(String line:normalized.split("\n")) if(!line.trim().isEmpty()) lines.add(line.trim());
        int codeIndex=lines.indexOf("商品编号");
        if(codeIndex<0 || codeIndex+1>=lines.size()) throw new IllegalArgumentException("请打开商品详情页并等待参数加载完成，再点击读取。当前页面未找到商品编号。");
        if(!expectedCode.equals(lines.get(codeIndex+1))) throw new IllegalArgumentException("页面商品编号与本次查询不一致，未导入任何信息");
        int begin=lines.indexOf("商品参数");
        if(begin<0) throw new IllegalArgumentException("商品参数表尚未加载，请稍后再试");
        LinkedHashMap<String,String> attrs=new LinkedHashMap<>();
        for(int i=begin+1;i+1<lines.size();i++) {
            String key=lines.get(i);
            if(Arrays.asList("替代料","买了又买","精选工业品推荐","全部替代料","相似推荐","其他推荐","商品概述","商品特性").contains(key) || key.startsWith("全部替代料")) break;
            if(knownAttribute(key)) attrs.put(key,lines.get(i+1));
        }
        if(attrs.isEmpty()) throw new IllegalArgumentException("未读取到受支持的商品参数，请在页面上核对或手动填写");
        Part p=new Part(); p.code=expectedCode; p.platform="立创商城";
        p.model=after(lines,"商品型号",codeIndex+8);
        p.brand=after(lines,"品牌名称",codeIndex+8);
        p.footprint=after(lines,"商品封装",codeIndex+8);
        p.category=LabelCategories.canonical(pick(attrs,"商品目录"));
        p.resistance=PassiveValues.first(pick(attrs,"阻值","电阻值"),"resistance","电阻");
        p.capacitance=PassiveValues.first(pick(attrs,"容值","电容量"),"capacitance","电容");
        p.inductance=PassiveValues.first(pick(attrs,"感值","电感值"),"inductance","电感");
        p.color=pick(attrs,"发光颜色","颜色");
        p.voltage=rating(pick(attrs,"额定电压","电压","耐压","工作电压","直流反向耐压(Vr)","最大反向电压"),"voltage");
        p.power=rating(pick(attrs,"额定功率","功率","耗散功率(Pd)"),"power");
        p.current=rating(pick(attrs,"额定电流","整流电流","额定电流(Irms)","电流","测试电流"),"current");
        StringBuilder description=new StringBuilder();
        for(var a:attrs.entrySet()) description.append(a.getKey()).append(": ").append(a.getValue()).append('\n');
        p.description=description.toString().trim();
        return p;
    }
    private static boolean knownAttribute(String key) {
        return Arrays.asList("商品目录","电阻类型","阻值","电阻值","容值","电容量","感值","电感值","发光颜色","颜色","额定电压","电压","耐压","工作电压","直流反向耐压(Vr)","最大反向电压","额定功率","功率","耗散功率(Pd)","额定电流","整流电流","额定电流(Irms)","电流","测试电流","正向压降(Vf)","反向电流(Ir)","反向恢复时间(Trr)","非重复峰值浪涌电流 (Ifsm)","精度","误差","温度系数","电容介质","温度特性","饱和电流(Isat)","直流电阻(DCR)","压敏电压","钳位电压","工作电压(DC)","工作电压(AC)","峰值浪涌电流","能量","静态电容","静态功率","安装方式","工作温度").contains(key);
    }
    private static String pick(Map<String,String> attrs,String... names) {
        for(String name:names) { String value=attrs.get(name); if(value!=null && !Arrays.asList("-","—","","暂无").contains(value)) return value; }
        return "";
    }
    private static String rating(String value,String kind) {
        if(value.matches(".*[~～至@].*")) return ""; // 范围、测试条件保留在原始参数，不截断成单一额定值。
        return ElectricalValues.first(value,kind);
    }
    private static String after(List<String> lines,String name,int limit) {
        int at=lines.indexOf(name);
        return at>=0 && at+1<Math.min(limit,lines.size())?lines.get(at+1):"";
    }
}

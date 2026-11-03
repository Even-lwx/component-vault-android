package cn.componentvault.app;

import java.util.ArrayList;

/** 库存卡片和详情共用的信息优先级：类别、主要参数/颜色、封装/额定值、型号。 */
public final class PartDisplay {
    private PartDisplay() {}
    public static String category(Part p) { return p.category.isEmpty()?"未分类":p.category; }
    public static String primary(Part p) { return join(p.resistance,p.capacitance,p.inductance,p.color); }
    public static String ratings(Part p) {
        return join(label("封装",p.footprint),label("耐压/电压",p.voltage),label("功率",p.power),label("电流",p.current));
    }
    public static String reference(Part p) { return join(p.model,p.code,p.brand,p.platform); }
    public static String detail(Part p) {
        StringBuilder b=new StringBuilder();
        for(String[] row:new String[][]{{"阻值",p.resistance},{"容值",p.capacitance},{"感值",p.inductance},{"颜色/光色",p.color},
                {"封装",p.footprint},{"耐压/额定电压",p.voltage},{"额定功率",p.power},{"电流",p.current}})
            if(!row[1].isEmpty()) b.append(row[0]).append("：").append(row[1]).append('\n');
        b.append("型号：").append(missing(p.model)).append("\n编码：").append(missing(p.code))
                .append("\n品牌：").append(missing(p.brand)).append("\n平台：").append(missing(p.platform))
                .append("\n位置：").append(missing(p.location)).append("\n其他规格：").append(missing(p.description))
                .append("\n备注：").append(missing(p.note));
        return b.toString();
    }
    private static String label(String name,String value) { return value.isEmpty()?"":name+" "+value; }
    private static String missing(String value) { return value.isEmpty()?"—":value; }
    private static String join(String... values) {
        ArrayList<String> items=new ArrayList<>(); for(String value:values) if(!value.isEmpty()) items.add(value);
        return String.join(" · ",items);
    }
}

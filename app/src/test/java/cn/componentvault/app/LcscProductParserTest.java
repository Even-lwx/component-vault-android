package cn.componentvault.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LcscProductParserTest {
    private static final String DIODE="首页\n快恢复/高效率二极管\nFR107(RS1M)\n品牌名称\nKEXIN(科信)\n商品型号\nFR107(RS1M)\n商品编号\nC489136\n商品封装\nDO-214AC(SMA)\u200b\n库存总量\n现货:134050\n商品参数\n查看类似商品\n属性\t参数值\n商品目录\t快恢复/高效率二极管\n正向压降(Vf)\t1.3V@1A\n直流反向耐压(Vr)\t1kV\n整流电流\t1A\n反向电流(Ir)\t5uA\n耗散功率(Pd)\t-\n非重复峰值浪涌电流 (Ifsm)\t30A\n替代料\n买了又买\n容值\n100nF";
    @Test public void readsValidatedLiveDiodeParameterNames() {
        Part p=LcscProductParser.parse(DIODE,"C489136");
        assertEquals("FR107(RS1M)",p.model); assertEquals("DO-214AC(SMA)",p.footprint);
        assertEquals("KEXIN(科信)",p.brand); assertEquals("1 kV",p.voltage); assertEquals("1 A",p.current);
        assertEquals("",p.power); assertEquals("",p.capacitance); assertEquals(0,p.quantity);
        assertTrue(p.description.contains("正向压降(Vf): 1.3V@1A"));
    }
    @Test public void neverMistakesLedForwardDropForRatedVoltage() {
        String text="品牌名称\nXINGLIGHT(成兴光)\n商品型号\nXL-1005SURC\n商品编号\nC25503345\n商品封装\n0402\n商品参数\n商品目录\t发光二极管/LED\n发光颜色\t红色\n正向压降(Vf)\t1.8V~2.4V\n功率\t50mW\n测试电流\t20mA\n替代料";
        Part p=LcscProductParser.parse(text,"C25503345");
        assertEquals("红色",p.color); assertEquals("50 mW",p.power); assertEquals("20 mA",p.current);
        assertEquals("",p.voltage); assertTrue(p.description.contains("1.8V~2.4V"));
    }
    @Test public void readsActualResistorDecimalWithoutOcrRewrite() {
        Part p=LcscProductParser.parse("商品编号\nC123456\n商品参数\n商品目录\n厚膜电阻\n阻值\n10.2kΩ\n功率\n100mW\n买了又买\n阻值\n102.2kΩ","C123456");
        assertEquals("10.2 kΩ",p.resistance); assertEquals("100 mW",p.power);
    }
    @Test public void readsCapacitanceAndInductanceWithNamedFields() {
        Part p=LcscProductParser.parse("商品编号\nC123456\n商品参数\n商品目录\n贴片电容\n容值\n100nF\n额定电压\n50V","C123456");
        assertEquals("100 nF",p.capacitance); assertEquals("50 V",p.voltage);
        Part l=LcscProductParser.parse("商品编号\nC123456\n商品参数\n感值\n4.7uH\n额定电流\n2A\n饱和电流(Isat)\n3A","C123456");
        assertEquals("4.7 µH",l.inductance); assertEquals("2 A",l.current);
    }
    @Test(expected=IllegalArgumentException.class) public void differentCodeCannotBeImported() { LcscProductParser.parse(DIODE,"C25503345"); }
    @Test public void quantityAndPriceAreNeverCatalogFields() {
        assertFalse(LcscProductParser.fields(LcscProductParser.parse(DIODE,"C489136")).containsKey("quantity"));
        assertFalse(LcscProductParser.fields(LcscProductParser.parse(DIODE,"C489136")).containsKey("location"));
    }
    @Test public void ratingRangeStaysInDescription() {
        Part p=LcscProductParser.parse("商品编号\nC123456\n商品参数\n商品目录\n传感器\n电压\n3V~5V","C123456");
        assertEquals("",p.voltage); assertTrue(p.description.contains("3V~5V"));
    }
    @Test(expected=IllegalArgumentException.class) public void searchResultsCannotBeImported() { LcscProductParser.parse("C489136 FR107 买了又买 10kΩ","C489136"); }
    @Test(expected=IllegalArgumentException.class) public void notLoadedParametersAreRejected() { LcscProductParser.parse("商品编号\nC489136\n正在加载","C489136"); }
}

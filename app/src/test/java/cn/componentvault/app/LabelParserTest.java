package cn.componentvault.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LabelParserTest {
    @Test public void ledLabel() {
        Part p=LabelParser.parse("3349-B-E02-005\nC25503345 静\nROHS\n0402分光红贴片式发光二极管\n0402\nXL-1005SURC\nXINGLIGHT(成兴光)\nQTY:50个");
        assertEquals("C25503345",p.code); assertEquals("XL-1005SURC",p.model);
        assertEquals("0402",p.footprint); assertEquals("发光二极管/LED",p.category);
        assertEquals("XINGLIGHT(成兴光)",p.brand); assertEquals(50,p.quantity);
    }
    @Test public void diodeLabel() {
        Part p=LabelParser.parse("3434-A-D03-001\nC489136 静\n26+ ROHS\n耐压:1kV 电流:1A 反向恢复时间:500ns\nDO-214AC(SMA)\nFR107(RS1M)\nKEXIN(科信)\nQTY:50个");
        assertEquals("FR107(RS1M)",p.model); assertEquals("DO-214AC(SMA)",p.footprint);
        assertEquals("KEXIN(科信)",p.brand); assertEquals("二极管",p.category);
    }
    @Test public void structuredCode() {
        Part p=LabelParser.parse("{\"code\":\"C489136\",\"model\":\"FR107\",\"quantity\":50,\"package\":\"SMA\"}");
        assertEquals("FR107",p.model); assertEquals("SMA",p.footprint); assertEquals(50,p.quantity);
    }
    @Test public void unknownCodeDoesNotInventData() {
        Part p=LabelParser.parse("https://example.org/order/123456");
        assertEquals("",p.model); assertEquals("",p.code); assertEquals(0,p.quantity); assertTrue(p.raw.contains("https://"));
    }
    @Test public void emptyAndOverflow() {
        assertEquals("",LabelParser.parse(null).model);
        assertEquals(0,LabelParser.parse("QTY:999999999999999999999999999").quantity);
    }
    @Test public void mergedFieldsAndDigitLeadingModel() {
        Part p=LabelParser.parse("平台：立创商城\n型号：1N4148W 封装：SOD-123 品牌：NEXPERIA\nQTY：20");
        assertEquals("立创商城",p.platform); assertEquals("1N4148W",p.model);
        assertEquals("SOD-123",p.footprint); assertEquals("NEXPERIA",p.brand);
        assertEquals("1N4148W",LabelParser.parse("1N4148W\nSOT-23").model);
    }
    @Test public void footprintIsNotModelAndUnknownPlatformIsNotGuessed() {
        Part p=LabelParser.parse("C489136\nTSSOP-16\nSOT-23");
        assertEquals("",p.model); assertEquals("TSSOP-16",p.footprint); assertEquals("",p.platform);
    }
    @Test public void ocrWhitespaceAndFullWidthPunctuation() {
        Part p=LabelParser.parse("C 489136\nFR107 （ RS1M ）\nDO - 214AC （ SMA ）\nKEXIN （科信）");
        assertEquals("C489136",p.code); assertEquals("FR107(RS1M)",p.model);
        assertEquals("DO-214AC(SMA)",p.footprint); assertEquals("KEXIN(科信)",p.brand);
    }
    @Test public void singleUseAndResettableFusesGetUsefulCategories() {
        assertEquals("一次性保险丝",LabelParser.parse("一次性保险丝\n0603F 1A 32V").category);
        assertEquals("自恢复保险丝",LabelParser.parse("可恢复保险丝\nJK30 0.5A").category);
        assertEquals("保险丝",LabelParser.parse("Fuse 1206").category);
    }
    @Test public void neverGuessesPlatformFromBrandOrUnknownSupplier() {
        Part p=LabelParser.parse("KEXIN(科信)\nFR107\nSMA");
        assertEquals("KEXIN(科信)",p.brand); assertEquals("",p.platform);
    }
}

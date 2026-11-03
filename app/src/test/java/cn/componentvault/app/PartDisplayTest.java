package cn.componentvault.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PartDisplayTest {
    @Test public void typeAndCapacitanceComeBeforeModel() {
        Part p=new Part(); p.category="贴片电容"; p.capacitance="100 nF"; p.footprint="0402"; p.voltage="50 V"; p.model="GRM123";
        assertEquals("贴片电容",PartDisplay.category(p));
        assertEquals("100 nF",PartDisplay.primary(p));
        assertEquals("封装 0402 · 耐压/电压 50 V",PartDisplay.ratings(p));
        assertTrue(PartDisplay.detail(p).indexOf("100 nF")<PartDisplay.detail(p).indexOf("GRM123"));
    }
    @Test public void ledShowsItsColorAsMainAttribute() {
        Part p=new Part(); p.category=LabelCategories.LED; p.color="红色"; p.current="20 mA";
        assertEquals("红色",PartDisplay.primary(p));
        assertEquals("电流 20 mA",PartDisplay.ratings(p));
        assertFalse(PartDisplay.detail(p).contains("阻值："));
    }
    @Test public void ratingsFollowRequestedOrderAndDoNotInventDefaults() {
        Part p=new Part(); p.footprint="0603"; p.voltage="50 V"; p.power="0.25 W"; p.current="1 A";
        String ratings=PartDisplay.ratings(p);
        assertTrue(ratings.indexOf("0603")<ratings.indexOf("50 V"));
        assertTrue(ratings.indexOf("50 V")<ratings.indexOf("0.25 W"));
        assertTrue(ratings.indexOf("0.25 W")<ratings.indexOf("1 A"));
        assertEquals("未分类",PartDisplay.category(new Part()));
        assertEquals("",PartDisplay.primary(new Part()));
        assertEquals("",PartDisplay.ratings(new Part()));
    }
}

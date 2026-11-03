package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LabelCategoriesTest {
    @Test public void printedLedCategoryHasFullName() {
        Part p=LabelParser.parse("C25503345\n0402分光红贴片式发光二极管\nXL-1005SURC\nQTY:50个\n发光二极管/LED");
        assertEquals("发光二极管/LED",p.category);
        assertEquals("XL-1005SURC",p.model);
    }
    @Test public void truncatedFastRecoveryCategoryIsCompleted() {
        Part p=LabelParser.parse("C489136\n耐压:1kV 电流:1A\nDO-214AC(SMA)\nFR107(RS1M)\nQTY:50\n快恢复/高效率二...");
        assertEquals("快恢复/高效率二极管",p.category);
        assertEquals("FR107(RS1M)",p.model);
        assertEquals("DO-214AC(SMA)",p.footprint);
    }
    @Test public void punctuationVariantsAndLineBreaks() {
        assertEquals(LabelCategories.FAST_DIODE,LabelParser.parse("快恢复 ／ 高效率二…").category);
        assertEquals(LabelCategories.FAST_DIODE,LabelParser.parse("类别:快恢复/高效率二⋯").category);
        assertEquals(LabelCategories.LED,LabelParser.parse("发光二极管 / LED").category);
        assertEquals(LabelCategories.FAST_DIODE,LabelParser.parse("分类:快恢复/高效率二极管").category);
        assertEquals(LabelCategories.FAST_DIODE,LabelParser.parse("快恢復／高效率二極…").category);
    }
    @Test public void explicitPrintedCategoryWinsOverDescription() {
        assertEquals("肖特基二极管",LabelParser.parse("高速开关管和普通二极管\n肖特基二极管\n型号:SS14").category);
        assertEquals("贴片电阻",LabelParser.parse("电阻\n贴片电阻\n10kΩ").category);
    }
    @Test public void unknownModelDoesNotFabricateSpecificClass() {
        assertEquals("二极管",LabelParser.parse("FR107(RS1M)").category);
        assertEquals("二极管",LabelParser.parse("分类:二极管\nFR107(RS1M)").category);
    }
    @Test public void customCategoriesParticipateInUniqueCompletion() {
        List<String> custom=Arrays.asList("高压 保险丝","实验用温度传感器");
        assertEquals("高压 保险丝",LabelParser.parse("分类:高压保险…",custom).category);
        assertEquals("高压 保险丝",LabelParser.parse("高压 保险丝",custom).category);
        assertEquals("实验用温度传感器",LabelCategories.complete("实验用温度传...",custom));
    }
    @Test public void ambiguousAndUnknownPrefixesAreNotInvented() {
        assertEquals("贴片...",LabelCategories.complete("贴片...",Collections.emptyList()));
        assertEquals("未收录类...",LabelCategories.complete("未收录类...",Collections.emptyList()));
        assertEquals("贴片...",LabelParser.parse("贴片...").category);
    }
    @Test public void fallbackVotesDoNotOverrideSpecificPrintedCategory() {
        ScanSession s=new ScanSession();
        for(int i=0;i<8;i++) s.observe("FR107(RS1M)",Collections.emptyList(),true,i*500);
        s.observe("FR107(RS1M)\n快恢复/高效率二...",Collections.emptyList(),true,5000);
        assertEquals(LabelCategories.FAST_DIODE,s.winner("category"));
        assertEquals(LabelCategories.FAST_DIODE,LabelParser.parse(s.result()).category);
    }
}

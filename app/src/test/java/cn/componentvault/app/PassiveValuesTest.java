package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PassiveValuesTest {
    @Test public void resistorPrintedUnitsAndChinese() {
        assertEquals("10 kΩ",PassiveValues.first("贴片电阻 10kΩ ±1% 0603","resistance",""));
        assertEquals("4.7 kΩ",PassiveValues.first("阻值:4.7千欧姆","resistance",""));
        assertEquals("10 mΩ",PassiveValues.first("电阻 10mΩ 1%","resistance",""));
        assertEquals("10 MΩ",PassiveValues.first("10MΩ","resistance",""));
        assertEquals("0 Ω",PassiveValues.first("0 ohm","resistance",""));
    }
    @Test public void decimalLetterNotationRequiresContext() {
        assertEquals("4.7 kΩ",PassiveValues.first("4K7 ±1% 0603","resistance","电阻"));
        assertEquals("2.2 Ω",PassiveValues.first("阻值:2R2","resistance",""));
        assertEquals("220 mΩ",PassiveValues.first("R22","resistance","电阻"));
        assertEquals("",PassiveValues.first("4K7","resistance",""));
        assertEquals("4.7 µH",PassiveValues.first("4u7","inductance","电感"));
        assertEquals("4.7 µH",PassiveValues.first("4R7","inductance","电感"));
        assertEquals("",PassiveValues.first("SWPA4020S4R7MT","inductance","电感"));
    }
    @Test public void capacitorAliasesAndEquivalentValues() {
        assertEquals("100 nF",PassiveValues.first("电容 0.1uF 50V X7R ±10% 0402","capacitance",""));
        assertEquals("100 nF",PassiveValues.first("100nF","capacitance",""));
        assertEquals("4.7 µF",PassiveValues.first("容值:4.7微法拉","capacitance",""));
        assertEquals("22 pF",PassiveValues.first("22 pF C0G","capacitance",""));
        assertEquals("4.7 µF",PassiveValues.first("４．７μＦ","capacitance",""));
    }
    @Test public void inductanceUnitsAndWhitespace() {
        assertEquals("4.7 µH",PassiveValues.first("功率电感 4.7 uH ±20% 2A","inductance",""));
        assertEquals("10 mH",PassiveValues.first("10毫亨利","inductance",""));
        assertEquals("220 nH",PassiveValues.first("220nH","inductance",""));
        assertEquals("4.7 µH",PassiveValues.first("4. 7 u H","inductance",""));
        assertEquals("10 kΩ",PassiveValues.first("10 k Ω","resistance",""));
        assertEquals("100 nF",PassiveValues.first("100 n F","capacitance",""));
    }
    @Test public void noGuessFromSkuPackageAndBareCode() {
        String text="C1234567\nGRM188R71C104KA01D\n0603\n104\n50V\nQTY:100";
        for(String kind:Arrays.asList("resistance","capacitance","inductance")) assertEquals("",PassiveValues.first(text,kind,""));
        assertEquals("",PassiveValues.first("0603 104","capacitance","电容"));
        assertEquals("",PassiveValues.first("4.7uH","capacitance","电容"));
    }
    @Test public void fullLabelsExtractAndInferType() {
        Part c=LabelParser.parse("C123456\n电容\n100nF 50V X7R\n0402\nQTY:50");
        assertEquals("100 nF",c.capacitance); assertEquals("0402",c.footprint); assertEquals("电容",c.category);
        Part r=LabelParser.parse("贴片电阻\n10K ±1%\n0603");
        assertEquals("10 kΩ",r.resistance); assertEquals("",r.capacitance);
        Part l=LabelParser.parse("4.7uH");
        assertEquals("4.7 µH",l.inductance); assertEquals("电感",l.category);
    }
    @Test public void sessionRoundTripAndParameterOnlyLabels() {
        ScanSession s=new ScanSession();
        for(int i=0;i<5;i++) s.observeCandidates(Arrays.asList("100nF","0.1µF"),Collections.emptyList(),true,i*500);
        assertTrue(s.hasData());
        assertEquals("100 nF",LabelParser.parse(s.result()).capacitance);
        assertEquals(1,OcrCandidates.read(s.result(),"capacitance").size());
    }
    @Test public void differentValuesAreRetainedForReview() {
        ScanSession s=new ScanSession();
        s.observeCandidates(Arrays.asList("100nF","100pF"),Collections.emptyList(),true,0);
        assertEquals(2,OcrCandidates.read(s.result(),"capacitance").size());
    }
}

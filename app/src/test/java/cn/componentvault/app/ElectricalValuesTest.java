package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ElectricalValuesTest {
    @Test public void diodeLabelRetainsVoltageAndCurrent() {
        Part p=LabelParser.parse("快恢复/高效率二...\n耐压:1kV 电流:1A 反向恢复时间:500ns\nFR107(RS1M)\nDO-214AC(SMA)");
        assertEquals("1 kV",p.voltage); assertEquals("1 A",p.current); assertEquals("",p.power);
    }
    @Test public void capacitorAndResistorRatings() {
        assertEquals("50 V",LabelParser.parse("贴片电容\n100nF 50V X7R\n0402").voltage);
        assertEquals("0.25 W",LabelParser.parse("贴片电阻\n10kΩ ±1% 0.25W\n0603").power);
        assertEquals("0.125 W",ElectricalValues.first("额定功率:1/8W","power"));
    }
    @Test public void chineseUnitsAndOcrSpaces() {
        assertEquals("1 kV",ElectricalValues.first("耐压:1千伏","voltage"));
        assertEquals("500 mA",ElectricalValues.first("电流:500毫安","current"));
        assertEquals("250 mW",ElectricalValues.first("250 m W","power"));
        assertEquals("20 µA",ElectricalValues.first("20 μA","current"));
    }
    @Test public void primaryLedColorComesFromPrintedDescription() {
        Part p=LabelParser.parse("发光二极管/LED\n0402分光红贴片式发光二极管\nXL-1005SURC");
        assertEquals("红色",p.color); assertEquals("",p.current); assertEquals("",p.voltage);
    }
    @Test public void warmWhiteIsNotReducedToWhite() {
        assertEquals("暖白色",ElectricalValues.color("LED Warm White"));
        assertEquals("冷白色",ElectricalValues.color("光色:冷白"));
        assertEquals("",ElectricalValues.color("infrared sensor MODEL RED123"));
    }
    @Test public void skuNumbersAndPackagesNeverInventRatings() {
        for(String kind:Arrays.asList("voltage","power","current")) {
            assertEquals("",ElectricalValues.first("GRM188R71H104KA93D\n0402\nC489136\nDO-214AC(SMA)",kind));
        }
        assertEquals("",ElectricalValues.first("1/0W","power"));
    }
    @Test public void multiFrameRoundTripRetainsNewFields() {
        ScanSession s=new ScanSession();
        for(int i=0;i<4;i++) s.observe("发光二极管/LED\n颜色:红色\n电压:2V\n功率:0.1W\n电流:20mA\n型号:TEST100",Collections.emptyList(),true,i*300);
        Part p=LabelParser.parse(s.result());
        assertEquals("红色",p.color); assertEquals("2 V",p.voltage); assertEquals("0.1 W",p.power); assertEquals("20 mA",p.current);
        assertEquals("20 mA",OcrCandidates.read(s.result(),"current").get(0));
    }
}

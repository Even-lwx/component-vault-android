package cn.componentvault.app;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class ScanSessionTest {
    private static final String LABEL="C489136\nFR107(RS1M)\nDO-214AC(SMA)\nKEXIN(科信)\nQTY:50\n立创商城";
    private void frame(ScanSession s,String text,long now) { s.observe(text,Collections.emptyList(),true,now); }
    private ScanSession populated() {
        ScanSession s=new ScanSession(); for(int i=0;i<6;i++) frame(s,LABEL,i*500); return s;
    }
    @Test public void visibleLabelNeverLooksAbsentEvenWhenStable() {
        ScanSession s=populated(); frame(s,LABEL,60000); assertFalse(s.sustainedAbsence(60000));
        assertEquals("FR107(RS1M)",s.winner("model")); assertEquals("立创商城",s.winner("platform"));
    }
    @Test public void sustainedAbsenceIsOnlySuggestedAfterThreshold() {
        ScanSession s=populated();
        for(int i=0;i<5;i++) frame(s,"",3000+i*500);
        assertFalse(s.sustainedAbsence(5000));
        frame(s,"",6000); assertTrue(s.sustainedAbsence(6000));
        assertFalse(s.canComplete(false));
    }
    @Test public void briefMissAndRecognizerFailureDoNotSuggestRemoval() {
        ScanSession s=populated(); frame(s,"",3000); frame(s,LABEL,5000); assertFalse(s.sustainedAbsence(9000));
        for(int i=0;i<8;i++) s.observe("",Collections.emptyList(),false,10000+i*1000);
        assertFalse(s.sustainedAbsence(20000));
    }
    @Test public void singleSlowEmptyFrameIsNotEnough() {
        ScanSession s=populated(); frame(s,"",3000); frame(s,"",10000); assertFalse(s.sustainedAbsence(10000));
    }
    @Test public void votesRejectSingleBadReadAndRoundTripFields() {
        ScanSession s=populated(); frame(s,"FR1O7(RS1M)\n封装:SMA",3000);
        Part p=LabelParser.parse(s.result());
        assertEquals("FR107(RS1M)",p.model); assertEquals("DO-214AC(SMA)",p.footprint);
        assertEquals("KEXIN(科信)",p.brand); assertEquals(50,p.quantity); assertEquals("立创商城",p.platform);
    }
    @Test public void differentCodeCannotPolluteLockedLabel() {
        ScanSession s=populated(); frame(s,"C25503345\nXL-1005SURC\n0402",3000);
        assertTrue(s.conflict()); assertEquals("C489136",s.winner("code")); assertEquals("FR107(RS1M)",s.winner("model"));
    }
    @Test public void allMissingNeverCompletes() {
        ScanSession s=new ScanSession(); for(int i=0;i<20;i++) frame(s,"",i*1000);
        assertFalse(s.sustainedAbsence(30000)); assertFalse(s.hasData()); assertFalse(s.canComplete(true));
    }
}

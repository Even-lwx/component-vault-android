package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class QrPayloadTest {
    @Test public void scannerAcceptsOneProductAndRejectsMixedLabels() {
        String a="{pc:C489136,pm:FR107(RS1M),qty:50}";
        assertEquals(a,QrPayload.uniqueProductPayload(Arrays.asList("order123",a,a)));
        assertNull(QrPayload.uniqueProductPayload(Arrays.asList(a,"{pc:C25503345,pm:XL-1005SURC}")));
        assertNull(QrPayload.uniqueProductPayload(Collections.singletonList("order123")));
    }
    @Test public void realDiodeQrPayload() {
        Part p=QrPayload.parse("{on:SO26092510135,pc:C489136,pm:FR107(RS1M),qty:50,mc:,cc:1,pdi:239533774,hp:11}",Collections.emptyList());
        assertEquals("C489136",p.code); assertEquals("FR107(RS1M)",p.model); assertEquals(50,p.quantity);
        assertEquals("",p.resistance); assertEquals("",p.capacitance); assertEquals("",p.inductance);
    }
    @Test public void realLedQrPayload() {
        Part p=QrPayload.parse("{on:SO26091018198,pc:C25503345,pm:XL-1005SURC,qty:50,mc:,cc:1,pdi:236130070,hp:11}",Collections.emptyList());
        assertEquals("C25503345",p.code); assertEquals("XL-1005SURC",p.model); assertEquals(50,p.quantity);
    }
    @Test public void qrIdentityOverridesRepeatedWrongOcr() {
        ScanSession s=new ScanSession();
        for(int i=0;i<8;i++) s.observe("code:C489138\nmodel:FR1O7(RS1M)\nQTY:500",Collections.emptyList(),true,i*300);
        s.observeCandidates(Collections.singletonList("code:C489138\nmodel:FR1O7(RS1M)\nQTY:500"),
                Collections.singletonList("{pc:C489136,pm:FR107(RS1M),qty:50}"),true,3000);
        assertEquals("C489136",s.winner("code")); assertEquals("FR107(RS1M)",s.winner("model"));
        assertEquals("50",s.winner("quantity"));
        assertTrue(s.result().contains("source.model: 二维码"));
    }
    @Test public void qrParameterBeatsDecimalOcrErrorButKeepsEvidence() {
        ScanSession s=new ScanSession();
        for(int i=0;i<5;i++) s.observe("电阻 102.2kΩ",Collections.emptyList(),true,i*300);
        s.observeCandidates(Collections.singletonList("电阻 102.2kΩ"),Collections.singletonList("resistance:10.2kΩ"),true,2000);
        assertEquals("10.2 kΩ",s.winner("resistance"));
        assertTrue(OcrCandidates.read(s.result(),"resistance").contains("102.2 kΩ"));
    }
    @Test public void unrelatedQrCodesNeverMerge() {
        ScanSession s=new ScanSession();
        s.observeCandidates(Collections.emptyList(),Arrays.asList("{pc:C489136,pm:FR107}","{pc:C25503345,pm:XL-1005SURC}"),true,0);
        assertTrue(s.conflict()); assertFalse(s.hasData());
    }
}

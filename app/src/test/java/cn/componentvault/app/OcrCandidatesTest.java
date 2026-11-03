package cn.componentvault.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class OcrCandidatesTest {
    @Test public void disagreementsRemainVisibleForManualSelection() {
        ScanSession s=new ScanSession();
        for(int i=0;i<5;i++) s.observeCandidates(Arrays.asList("FR107(RS1M)","FR1O7(RS1M)"),Collections.emptyList(),true,i*500);
        String raw=s.result();
        assertTrue(OcrCandidates.read(raw,"model").contains("FR107(RS1M)"));
        assertTrue(OcrCandidates.read(raw,"model").contains("FR1O7(RS1M)"));
        assertEquals("FR107(RS1M)",s.winner("model"));
    }

    @Test public void identicalChineseAndLatinOutputsOnlyVoteOncePerFrame() {
        ScanSession s=new ScanSession();
        s.observeCandidates(Arrays.asList("FR107(RS1M)","FR107(RS1M)"),Collections.emptyList(),true,0);
        assertEquals(1,s.alternatives("model").size());
    }

    @Test public void oneBadFrameCannotReplaceStableCandidate() {
        ScanSession s=new ScanSession();
        for(int i=0;i<6;i++) s.observeCandidates(Collections.singletonList("FR107(RS1M)"),Collections.emptyList(),true,i*300);
        s.observeCandidates(Collections.singletonList("FR1O7(RS1M)"),Collections.emptyList(),true,2000);
        assertEquals("FR107(RS1M)",s.winner("model"));
    }
}

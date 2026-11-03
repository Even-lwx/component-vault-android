package cn.componentvault.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LookupWindowTest {
    @Test public void timesOutEvenIfPageNeverFinishes() {
        LookupWindow w=new LookupWindow();w.begin(1000);
        assertFalse(w.expired(60999));assertTrue(w.expired(61000));
    }
    @Test public void navigationDoesNotRenewDeadline() {
        LookupWindow w=new LookupWindow();w.begin(1000);
        w.resume(40000);assertTrue(w.expired(61000));
    }
    @Test public void loginPausesAndReturningStartsNewWindow() {
        LookupWindow w=new LookupWindow();w.begin(0);w.pause();
        assertFalse(w.expired(100000));w.resume(100000);
        assertFalse(w.expired(159999));assertTrue(w.expired(160000));
    }
    @Test public void explicitRetryStartsFreshWindow() {
        LookupWindow w=new LookupWindow();w.begin(0);assertTrue(w.expired(60000));
        w.begin(70000);assertFalse(w.expired(70000));assertTrue(w.expired(130000));
    }
}

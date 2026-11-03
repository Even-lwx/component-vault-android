package cn.componentvault.app;

/** 整次查询共用截止时间，页面跳转/资源未完成均不能无限延长等待。 */
public final class LookupWindow {
    public static final long TIMEOUT_MS=60000;
    private long deadline;
    private boolean paused;
    public void begin(long now) { deadline=now+TIMEOUT_MS; paused=false; }
    public void pause() { paused=true; }
    public void resume(long now) { if(paused)begin(now); }
    public boolean expired(long now) { return !paused && deadline>0 && now>=deadline; }
    public boolean paused() { return paused; }
}

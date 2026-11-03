package cn.componentvault.app;

import java.util.*;

/** 单张标签的多帧候选投票及离场判定，不依赖 Android，便于验证时间边界。 */
public final class ScanSession implements java.io.Serializable {
    private static final long serialVersionUID = 4L;
    private static final String[] FIELDS = {"platform", "code", "model", "brand", "footprint", "category", "resistance", "capacitance", "inductance", "color", "voltage", "power", "current", "description", "quantity"};
    private final Map<String, LinkedHashMap<String,Integer>> votes = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String,Integer>> qrVotes = new LinkedHashMap<>();
    private final LinkedHashSet<String> evidence = new LinkedHashSet<>();
    private long firstSeen=-1, absentSince=-1;
    private int absentFrames, frames;
    private boolean conflict;
    private final List<String> categories;

    public ScanSession() { this(Collections.emptyList()); }
    public ScanSession(List<String> categories) {
        this.categories=new ArrayList<>(categories);
        for(String field:FIELDS) { votes.put(field,new LinkedHashMap<>()); qrVotes.put(field,new LinkedHashMap<>()); }
    }

    public void observe(String text, List<String> codes, boolean successful, long now) {
        observeCandidates(Collections.singletonList(text),codes,successful,now);
    }

    public void observeCandidates(List<String> texts,List<String> codes,boolean successful,long now) {
        // 失败、低帧率长间隔均不能用作“标签已离开”的证据。
        if(!successful) { absentSince=-1; absentFrames=0; return; }
        ArrayList<Part> candidates=new ArrayList<>();
        Set<Part> qrCandidates=Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder combined=new StringBuilder();
        for(String text:texts) if(text!=null && !text.trim().isEmpty()) {
            combined.append(text.trim()).append('\n'); candidates.add(LabelParser.parse(text,categories));
        }
        for(String code:codes) if(code!=null && !code.trim().isEmpty()) {
            combined.append("[二维码]\n").append(code.trim()).append('\n');
            Part p=QrPayload.parse(code,categories); candidates.add(p); qrCandidates.add(p);
        }
        boolean visible=combined.length()>0;
        if(!visible) {
            if(firstSeen>=0) { if(absentSince<0) absentSince=now; absentFrames++; }
            return;
        }
        absentSince=-1; absentFrames=0;
        String locked=winner("code");
        Set<String> qrCodes=new HashSet<>();
        for(Part p:qrCandidates) if(!p.code.isEmpty()) qrCodes.add(p.code);
        if(qrCodes.size()>1) { conflict=true; return; }
        if(qrCodes.size()==1 && qrVotes.get("code").isEmpty()) locked=qrCodes.iterator().next();
        final String lockedCode=locked;
        LinkedHashSet<String> observedCodes=new LinkedHashSet<>();
        for(Part p:candidates) if(!p.code.isEmpty()) observedCodes.add(p.code);
        if(!locked.isEmpty() && count("code",locked)>=3 && !observedCodes.isEmpty()) {
            boolean agrees=observedCodes.stream().anyMatch(s->s.equalsIgnoreCase(lockedCode));
            if(!agrees) { conflict=true; return; } // 不把另一张标签合并进当前标签。
            candidates.removeIf(p->!p.code.isEmpty() && !p.code.equalsIgnoreCase(lockedCode));
        }
        conflict=false;
        if(firstSeen<0) firstSeen=now;
        frames++;
        Map<String,Set<String>> frameValues=new HashMap<>();
        Map<String,Set<String>> frameQr=new HashMap<>();
        for(String field:FIELDS) frameValues.put(field,new HashSet<>());
        for(String field:FIELDS) frameQr.put(field,new HashSet<>());
        for(Part p:candidates) {
            String[] values={p.platform,p.code,p.model,p.brand,p.footprint,p.category,p.resistance,p.capacitance,p.inductance,p.color,p.voltage,p.power,p.current,p.description,p.quantity>0?Long.toString(p.quantity):""};
            for(int i=0;i<FIELDS.length;i++) if(!values[i].isEmpty()) {
                LinkedHashMap<String,Integer> bucket=votes.get(FIELDS[i]);
                String value=values[i];
                for(String existing:bucket.keySet()) if(existing.equalsIgnoreCase(value)) { value=existing; break; }
                if(qrCandidates.contains(p) && frameQr.get(FIELDS[i]).add(value)) {
                    LinkedHashMap<String,Integer> qr=qrVotes.get(FIELDS[i]);
                    if(qr.size()<64 || qr.containsKey(value)) qr.put(value,qr.getOrDefault(value,0)+1);
                }
                if(!frameValues.get(FIELDS[i]).add(value.toUpperCase(Locale.ROOT))) continue;
                if(bucket.size()<64 || bucket.containsKey(value)) bucket.put(value,bucket.getOrDefault(value,0)+1);
            }
        }
        String sample=combined.toString().trim();
        if(evidence.size()<60 && !sample.isEmpty()) evidence.add(sample.substring(0,Math.min(sample.length(),2048)));
    }

    public boolean sustainedAbsence(long now) {
        return frames>=5 && firstSeen>=0 && now-firstSeen>=2000 && absentFrames>=5
                && absentSince>=0 && now-absentSince>=3000 && hasData();
    }

    public boolean hasData() { return !winner("code").isEmpty() || !winner("model").isEmpty() || !winner("resistance").isEmpty() || !winner("capacitance").isEmpty() || !winner("inductance").isEmpty(); }
    public boolean canComplete(boolean userConfirmedLabelRemoved) { return userConfirmedLabelRemoved && hasData(); }
    public boolean conflict() { return conflict; }
    public int frames() { return frames; }
    public String winner(String field) {
        if(!qrVotes.get(field).isEmpty()) return qrVotes.get(field).entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        String best=""; int score=0;
        for(var entry:votes.get(field).entrySet()) {
            if(field.equals("category") && votes.get(field).keySet().stream().anyMatch(name->LabelCategories.moreSpecific(name,entry.getKey()))) continue;
            if(entry.getValue()>score) { best=entry.getKey(); score=entry.getValue(); }
        }
        return best;
    }
    private int count(String field,String value) { return votes.get(field).getOrDefault(value,0); }
    public String summary() {
        StringBuilder b=new StringBuilder();
        String[] ordered={"category","resistance","capacitance","inductance","color","footprint","voltage","power","current","quantity","model","code","brand","platform","description"};
        String[] labels={"类别","阻值","容值","感值","颜色","封装","耐压/电压","功率","电流","数量","型号","编码","品牌","平台","其他规格"};
        for(int i=0;i<ordered.length;i++) {
            String field=ordered[i], value=winner(field);
            int total=0; for(int n:votes.get(field).values()) total+=n;
            boolean stable=!value.isEmpty() && count(field,value)>=3 && count(field,value)*2>total;
            b.append(labels[i]).append("：").append(value.isEmpty()?"未读到":value)
                    .append(value.isEmpty()?"":!qrVotes.get(field).isEmpty()?"  来自二维码":stable?"  多帧一致，仍需核对":"  待核对")
                    .append(votes.get(field).size()>1?"（存在其他候选）":"").append('\n');
        }
        return b.toString().trim();
    }

    public String result() {
        StringBuilder b=new StringBuilder();
        for(String field:FIELDS) if(!winner(field).isEmpty()) b.append(field).append(": ").append(winner(field)).append('\n');
        b.append("\n--- 字段候选 ---\n");
        for(String field:FIELDS) for(String value:alternatives(field))
            b.append("candidate.").append(field).append(": ").append(value.replace('\n',' ')).append('\n');
        for(String field:FIELDS) if(!qrVotes.get(field).isEmpty()) b.append("source.").append(field).append(": 二维码\n");
        b.append("\n--- 多帧原始识别记录 ---\n");
        for(String raw:evidence) b.append(raw).append("\n\n");
        return b.toString();
    }

    public List<String> alternatives(String field) {
        ArrayList<String> values=new ArrayList<>(votes.get(field).keySet());
        values.sort((a,b)->Integer.compare(count(field,b),count(field,a)));
        return values.subList(0,Math.min(8,values.size()));
    }
}

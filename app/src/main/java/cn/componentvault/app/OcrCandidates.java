package cn.componentvault.app;

import java.util.*;

/** 型号中的相似字符不自动改写，仅在用户核对时显示实测候选。 */
public final class OcrCandidates {
    private OcrCandidates() {}
    public static List<String> read(String raw,String field) {
        LinkedHashSet<String> values=new LinkedHashSet<>();
        String start="candidate."+field+": ";
        for(String line:raw.split("\n")) if(line.startsWith(start)) {
            String value=line.substring(start.length()).trim();
            if(!value.isEmpty()) values.add(value);
        }
        return new ArrayList<>(values);
    }
}

package cn.componentvault.app;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** 简单表格文件读写，保留单元格值；不执行公式或宏。 */
public final class BomFiles {
    public static final class Sheet implements Serializable {
        public final String name;
        public final List<List<String>> rows;
        public Sheet(String name,List<List<String>> rows) { this.name=name; this.rows=rows; }
    }
    private static final int MAX_BYTES=20*1024*1024, MAX_ROWS=10000, MAX_COLS=100;
    private BomFiles() {}
    public static List<Sheet> read(byte[] bytes,String name) throws Exception {
        if(bytes.length>MAX_BYTES) throw new IOException("文件超过 20MB，请拆分后导入");
        if(bytes.length>1 && bytes[0]=='P' && bytes[1]=='K') return xlsx(bytes);
        if(bytes.length>1 && (bytes[0]&255)==0xd0 && (bytes[1]&255)==0xcf) throw new IOException("暂不支持旧版 XLS，请另存为 XLSX 或 CSV");
        String text;
        if(bytes.length>1 && (bytes[0]&255)==255 && (bytes[1]&255)==254) text=new String(bytes,StandardCharsets.UTF_16LE);
        else if(bytes.length>1 && (bytes[0]&255)==254 && (bytes[1]&255)==255) text=new String(bytes,StandardCharsets.UTF_16BE);
        else {
            try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
            catch(CharacterCodingException e) { text=new String(bytes,Charset.forName("GB18030")); }
        }
        text=text.replaceFirst("^\uFEFF","");
        char delimiter=',';
        String first=text.split("\\r?\\n",2)[0];
        if(first.contains("\t")) delimiter='\t'; else if(!first.contains(",") && first.contains(";")) delimiter=';';
        return Collections.singletonList(new Sheet(name,csv(text,delimiter)));
    }
    public static byte[] limitedRead(InputStream in) throws IOException {
        if(in==null) throw new IOException("无法读取附件，请从聊天文件菜单分享或选择文件");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[16384]; int n;
        while((n=in.read(buffer))>=0) { if(out.size()+n>MAX_BYTES) throw new IOException("文件超过 20MB"); out.write(buffer,0,n); }
        return out.toByteArray();
    }
    public static List<List<String>> csv(String text,char delimiter) throws IOException {
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder value=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"') {
                if(quoted && i+1<text.length() && text.charAt(i+1)=='"') { value.append('"'); i++; }
                else if(quoted || value.length()==0) quoted=!quoted; else value.append(c);
            } else if(!quoted && (c==delimiter || c=='\n' || c=='\r')) {
                row.add(value.toString()); value.setLength(0);
                if(row.size()>MAX_COLS) throw new IOException("最多支持 100 列");
                if(c!=delimiter) { rows.add(row); row=new ArrayList<>(); if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++; }
                if(rows.size()>MAX_ROWS) throw new IOException("最多支持 10000 行");
            } else value.append(c);
        }
        if(quoted) throw new IOException("CSV 引号不完整，请检查文件");
        if(value.length()>0 || !row.isEmpty()) { row.add(value.toString()); if(row.size()>MAX_COLS)throw new IOException("最多支持 100 列");rows.add(row); }
        if(rows.size()>MAX_ROWS)throw new IOException("最多支持 10000 行");
        if(rows.isEmpty()) throw new IOException("文件没有数据");
        return rows;
    }
    private static List<Sheet> xlsx(byte[] bytes) throws Exception {
        Map<String,byte[]> entries=new HashMap<>(); int expanded=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; int count=0;
            while((entry=zip.getNextEntry())!=null) {
                if(++count>2000) throw new IOException("工作簿文件过于复杂");
                ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
                while((n=zip.read(b))>=0) { expanded+=n; if(expanded>60*1024*1024) throw new IOException("工作簿解压后超过 60MB"); out.write(b,0,n); }
                if(entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) entries.put(entry.getName(),out.toByteArray());
            }
        }
        List<String> shared=new ArrayList<>();
        if(entries.containsKey("xl/sharedStrings.xml")) {
            NodeList items=xml(entries.get("xl/sharedStrings.xml")).getElementsByTagNameNS("*","si");
            for(int i=0;i<items.getLength();i++) shared.add(texts((Element)items.item(i),"t"));
        }
        Document workbook=xml(entries.get("xl/workbook.xml")),rels=xml(entries.get("xl/_rels/workbook.xml.rels"));
        Map<String,String> targets=new HashMap<>(); NodeList relationships=rels.getElementsByTagNameNS("*","Relationship");
        for(int i=0;i<relationships.getLength();i++) {
            Element e=(Element)relationships.item(i); if(e.getAttribute("TargetMode").equals("External")) continue;
            String target=e.getAttribute("Target"); targets.put(e.getAttribute("Id"),target.startsWith("/")?target.substring(1):"xl/"+target.replaceFirst("^\\./",""));
        }
        List<Sheet> result=new ArrayList<>(); NodeList sheets=workbook.getElementsByTagNameNS("*","sheet");
        if(sheets.getLength()>30) throw new IOException("最多支持 30 个工作表");
        for(int i=0;i<sheets.getLength();i++) {
            Element e=(Element)sheets.item(i); String path=targets.get(e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id"));
            if(path==null || !entries.containsKey(path)) continue;
            Document doc=xml(entries.get(path)); List<List<String>> rows=new ArrayList<>(); NodeList nodes=doc.getElementsByTagNameNS("*","row");
            for(int r=0;r<nodes.getLength();r++) {
                Element row=(Element)nodes.item(r); List<String> cells=new ArrayList<>(); NodeList all=row.getElementsByTagNameNS("*","c");
                for(int c=0;c<all.getLength();c++) {
                    Element cell=(Element)all.item(c); String ref=cell.getAttribute("r"); int index=0;
                    for(char letter:ref.toUpperCase(Locale.ROOT).toCharArray()) { if(letter<'A'||letter>'Z') break; index=index*26+letter-'A'+1; }
                    index=ref.isEmpty()?cells.size():index-1;
                    if(index<0 || index>=MAX_COLS) throw new IOException("最多支持 100 列");
                    while(cells.size()<=index) cells.add("");
                    String value=texts(cell,"v"),type=cell.getAttribute("t");
                    if(type.equals("s") && !value.isEmpty()) value=shared.get(Integer.parseInt(value));
                    else if(type.equals("inlineStr")) value=texts(cell,"t");
                    // 不执行公式，只使用工作簿已保存的缓存值；没有缓存的公式提醒用户。
                    if(cell.getElementsByTagNameNS("*","f").getLength()>0 && value.isEmpty()) throw new IOException("公式没有缓存结果，请用表格软件重新计算并保存，或导出 CSV");
                    cells.set(index,value);
                }
                rows.add(cells); if(rows.size()>MAX_ROWS) throw new IOException("最多支持 10000 行");
            }
            if(!rows.isEmpty()) result.add(new Sheet(e.getAttribute("name"),rows));
        }
        if(result.isEmpty()) throw new IOException("没有可读取的工作表"); return result;
    }
    private static Document xml(byte[] bytes) throws Exception {
        if(bytes==null) throw new IOException("XLSX 缺少工作簿结构");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        String declarations=new String(bytes,StandardCharsets.ISO_8859_1).replace("\u0000","").toUpperCase(Locale.ROOT);
        if(declarations.contains("<!DOCTYPE") || declarations.contains("<!ENTITY")) throw new IOException("工作簿包含不支持的 XML 实体声明");
        factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("不允许加载外部实体");});
        return builder.parse(new ByteArrayInputStream(bytes));
    }
    private static String texts(Element e,String name) {
        StringBuilder b=new StringBuilder(); NodeList texts=e.getElementsByTagNameNS("*",name);
        for(int i=0;i<texts.getLength();i++) b.append(texts.item(i).getTextContent()); return b.toString();
    }
    public static byte[] writeCsv(List<List<String>> rows) {
        StringBuilder b=new StringBuilder("\ufeff");
        for(List<String> row:rows) {
            for(int i=0;i<row.size();i++) {
                if(i>0)b.append(','); String value=row.get(i);
                if(value.matches("(?s)^[=+@\\-\\t\\r].*")) value="'"+value;
                b.append('"').append(value.replace("\"","\"\"")).append('"');
            }
            b.append("\r\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static byte[] writeXlsx(List<List<String>> rows) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) {
            entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"BOM匹配结果\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for(int r=0;r<rows.size();r++) {
                sheet.append("<row r=\"").append(r+1).append("\">");
                for(int c=0;c<rows.get(r).size();c++) {
                    int n=c+1; String col=""; while(n>0) { n--; col=(char)('A'+n%26)+col; n/=26; }
                    sheet.append("<c r=\"").append(col).append(r+1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escape(rows.get(r).get(c))).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>"); entry(zip,"xl/worksheets/sheet1.xml",sheet.toString());
        }
        return out.toByteArray();
    }
    private static void entry(ZipOutputStream z,String name,String data)throws IOException { z.putNextEntry(new ZipEntry(name)); z.write(data.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
    private static String escape(String s) { return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
}

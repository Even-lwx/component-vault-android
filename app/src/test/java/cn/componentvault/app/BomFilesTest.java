package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import java.nio.charset.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class BomFilesTest {
    @Test public void csvQuotedCommaNewlineAndEscapedQuotes() throws Exception {
        List<List<String>> data=BomFiles.csv("位号,数量,说明\r\n\"R1,R2\",2,\"a\"\"b\nnext\"\r\n",',');
        assertEquals(2,data.size());assertEquals("R1,R2",data.get(1).get(0));assertEquals("a\"b\nnext",data.get(1).get(2));
    }
    @Test public void chineseEncodingAndUtf16() throws Exception {
        String data="位号,数量,参数\nR1,2,10kΩ";
        assertEquals("位号",BomFiles.read(data.getBytes(Charset.forName("GB18030")),"a.csv").get(0).rows.get(0).get(0));
        byte[] text=("\uFEFF"+data).getBytes(StandardCharsets.UTF_16LE);
        assertEquals("10kΩ",BomFiles.read(text,"a.csv").get(0).rows.get(1).get(2));
    }
    @Test public void xlsxExportRoundTripsValuesWithoutFormulaExecution() throws Exception {
        List<List<String>> rows=Arrays.asList(Arrays.asList("位号","参数","数量"),Arrays.asList("C1,C2","0.1µF < & >","4"),Arrays.asList("=1+2","\"quoted\"","0"));
        byte[] output=BomFiles.writeXlsx(rows);
        assertEquals(rows,BomFiles.read(output,"matched.xlsx").get(0).rows);
    }
    @Test public void csvExportEscapesFormulaCells() throws Exception {
        byte[] out=BomFiles.writeCsv(Arrays.asList(Arrays.asList("型号","备注"),Arrays.asList("=HYPERLINK(1)","a,b")));
        assertEquals("'=HYPERLINK(1)",BomFiles.read(out,"a.csv").get(0).rows.get(1).get(0));
    }
    @Test(expected=IOException.class) public void unmatchedCsvQuoteIsReported() throws Exception { BomFiles.csv("a,b\n\"bad",','); }
    @Test(expected=IOException.class) public void legacyXlsIsRejectedClearly() throws Exception { BomFiles.read(new byte[]{(byte)0xd0,(byte)0xcf,0,0},"test.xls"); }
    @Test public void sparseXlsxCellsKeepCorrectColumnPositions() throws Exception {
        List<List<String>> rows=Arrays.asList(Arrays.asList("A","","C"),Arrays.asList("1","","3"));
        assertEquals(rows,BomFiles.read(BomFiles.writeXlsx(rows),"a.xlsx").get(0).rows);
    }
    @Test public void externalWorkbookSharedStringsAndMultipleSheets() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Map<String,String> files=new LinkedHashMap<>();
        files.put("xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='说明' sheetId='1' r:id='first'/><sheet name='BOM' sheetId='2' r:id='second'/></sheets></workbook>");
        files.put("xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='first' Target='/xl/worksheets/one.xml'/><Relationship Id='second' Target='worksheets/two.xml'/></Relationships>");
        files.put("xl/sharedStrings.xml","<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><si><t>位号</t></si><si><r><t>C</t></r><r><t>1</t></r></si></sst>");
        files.put("xl/worksheets/one.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>说明页</t></is></c></row></sheetData></worksheet>");
        files.put("xl/worksheets/two.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c></row><row r='2'><c r='A2' t='s'><v>1</v></c><c r='C2'><f>1+2</f><v>3</v></c></row></sheetData></worksheet>");
        try(ZipOutputStream zip=new ZipOutputStream(out)){for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        List<BomFiles.Sheet> sheets=BomFiles.read(out.toByteArray(),"external.xlsx");
        assertEquals(2,sheets.size());assertEquals("BOM",sheets.get(1).name);assertEquals(Arrays.asList("C1","","3"),sheets.get(1).rows.get(1));
    }
    @Test(expected=IOException.class) public void xmlExternalEntityIsRejected() throws Exception {
        byte[] good=BomFiles.writeXlsx(Arrays.asList(Arrays.asList("a")));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(good));ZipOutputStream zip=new ZipOutputStream(out)) {
            ZipEntry e;while((e=in.getNextEntry())!=null) {
                zip.putNextEntry(new ZipEntry(e.getName()));byte[] data=BomFiles.limitedRead(in);
                if(e.getName().equals("xl/workbook.xml"))data=("<!DOCTYPE workbook [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                zip.write(data);zip.closeEntry();
            }
        }
        BomFiles.read(out.toByteArray(),"a.xlsx");
    }
}

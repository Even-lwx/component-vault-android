package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BomMatchTest {
    private Part capacitor(long id,long quantity,String value,String footprint) {
        Part p=new Part();p.id=id;p.quantity=quantity;p.capacitance=value;p.footprint=footprint;p.category="电容";return p;
    }
    private BomMatch.Row request(long qty,String value,String footprint) {
        BomMatch.Row r=new BomMatch.Row();r.perSet=qty;r.value=value;r.request.capacitance=PassiveValues.first(value,"capacitance","电容");r.request.footprint=footprint;r.request.category="电容";return r;
    }
    @Test public void sharedStockIsNotCountedTwice() {
        Part p=capacitor(1,15,"100 nF","0402");List<BomMatch.Row> rows=Arrays.asList(request(4,"100nF","0402"),request(5,"0.1uF","0402"));
        BomMatch.calculate(rows,Collections.singletonList(p),new BomMatch.Rules(),2,true);
        assertEquals(8,rows.get(0).allocated);assertEquals(7,rows.get(1).allocated);assertEquals(3,rows.get(1).shortage);
        assertEquals(15,p.quantity);
    }
    @Test public void multipleLocationsCanSupplySameRow() {
        BomMatch.Row r=request(10,"100nF","0402");
        BomMatch.calculate(Collections.singletonList(r),Arrays.asList(capacitor(1,6,"100 nF","0402"),capacitor(2,7,"100 nF","0402")),new BomMatch.Rules(),1,true);
        assertEquals(10,r.allocated);assertEquals(0,r.shortage);assertEquals(2,r.allocations.size());
    }
    @Test public void wrongFootprintOrMissingValueCannotSilentlyMatch() {
        BomMatch.Rules rules=new BomMatch.Rules();BomMatch.Row r=request(2,"100nF","0402");
        assertFalse(BomMatch.matches(r,capacitor(1,10,"100 nF","0603"),rules));
        r.request.capacitance="";assertFalse(BomMatch.matches(r,capacitor(1,10,"100 nF","0402"),rules));
    }
    @Test public void codeAndColorMatchingAreUserSelectable() {
        BomMatch.Row r=request(1,"100nF","0402");r.request.code="C123456";r.request.color="red";
        Part p=capacitor(1,10,"100 nF","0402");p.code="C123456";p.color="红色";
        BomMatch.Rules rules=new BomMatch.Rules();rules.fields.clear();rules.fields.add("code");rules.fields.add("color");
        assertTrue(BomMatch.matches(r,p,rules));p.color="蓝色";assertFalse(BomMatch.matches(r,p,rules));
    }
    @Test public void editedAndManualRowsAreExportedWithoutChangingOriginal() {
        BomMatch.Row r=request(3,"100nF","0402");r.original=Arrays.asList("C1","2","0.1uF");r.reference="C1,C2";
        Part p=capacitor(1,4,"100 nF","0402");p.code="C123456";r.manual=true;r.selected=1;
        BomMatch.calculate(Collections.singletonList(r),Collections.singletonList(p),new BomMatch.Rules(),2,false);
        List<List<String>> data=BomMatch.export(Arrays.asList("位号","数量","值"),Collections.singletonList(r),Collections.singletonList(p),2);
        assertEquals("2",data.get(1).get(1));assertEquals("3",data.get(1).get(data.get(0).indexOf("修改每套数量")));
        assertEquals("2",data.get(1).get(data.get(0).indexOf("缺料数量")));
        assertEquals(4,p.quantity);
    }
    @Test public void rowMappingUsesReferenceForUnitlessResistorValues() {
        List<String> headers=Arrays.asList("Designator","Quantity","Value","Footprint");Map<String,Integer> map=new LinkedHashMap<>();for(String k:BomMatch.KEYS)map.put(k,BomMatch.guess(k,headers));
        List<BomMatch.Row> rows=BomMatch.rows(new BomFiles.Sheet("test",Arrays.asList(headers,Arrays.asList("R1","2","10K","0603"),Arrays.asList("C1","5","100n","0402"))),0,map);
        assertEquals("10 kΩ",rows.get(0).request.resistance);assertEquals("100 nF",rows.get(1).request.capacitance);
    }
    @Test public void invalidQuantitiesAreMarkedRatherThanAssumedOne() {
        Map<String,Integer> map=new HashMap<>();map.put("quantity",1);map.put("value",0);
        BomMatch.Row r=BomMatch.rows(new BomFiles.Sheet("test",Arrays.asList(Arrays.asList("value","qty"),Arrays.asList("100nF","invalid"))),0,map).get(0);
        assertFalse(r.issue.isEmpty());assertEquals(0,r.perSet);
    }
    @Test public void largeSetCalculationsDoNotOverflowInt() {
        BomMatch.Row r=request(1000000000L,"100nF","0402");
        BomMatch.calculate(Collections.singletonList(r),Collections.emptyList(),new BomMatch.Rules(),1000000,true);
        assertEquals(1000000000000000L,r.required);assertEquals(r.required,r.shortage);
    }
    @Test public void incompatibleDimensionDoesNotMatch() {
        BomMatch.Row r=request(1,"100nF","0402");Part p=new Part();p.id=1;p.footprint="0402";p.inductance="100 nH";
        assertFalse(BomMatch.matches(r,p,new BomMatch.Rules()));
    }
    @Test public void explicitNoMatchIsPreservedUntilRemapped() {
        BomMatch.Row r=request(1,"100nF","0402");r.manual=true;r.selected=-1;
        BomMatch.calculate(Collections.singletonList(r),Collections.singletonList(capacitor(1,10,"100 nF","0402")),new BomMatch.Rules(),1,true);
        assertEquals(-1,r.selected);assertEquals(1,r.shortage);
    }
}

package cn.componentvault.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class InventoryBrowserTest {
    private Part part(long id,String code,String category,long qty,String value) {Part p=new Part();p.id=id;p.code=code;p.category=category;p.quantity=qty;p.resistance=value;p.footprint="0603";return p;}
    @Test public void kindCountsIgnoreStorageLocations() {
        Part a=part(1,"C12345","电阻",10,"10 kΩ"),b=part(2,"C12345","电阻",5,"10 kΩ");a.location="A";b.location="B";
        List<Part> all=Arrays.asList(a,b,part(3,"C67890","电容",2,""));
        assertEquals(2,InventoryBrowser.kinds(all));assertEquals(Integer.valueOf(1),InventoryBrowser.categoryCounts(all).get("电阻"));
    }
    @Test public void unnumberedDifferentRatingsAreDifferentKinds() {
        Part a=part(1,"","电阻",10,"10 kΩ"),b=part(2,"","电阻",5,"10 kΩ");b.power="1 W";
        assertEquals(2,InventoryBrowser.kinds(Arrays.asList(a,b)));
    }
    @Test public void categoryCountOrderAndPinsAreDeterministic() {
        List<String> names=Arrays.asList("电阻","电容","电感");Map<String,Integer> counts=new HashMap<>();counts.put("电阻",8);counts.put("电容",12);
        assertEquals(Arrays.asList("电容","电阻"),InventoryBrowser.categories(names,counts,Collections.emptySet(),Collections.emptyMap(),0,true,"全部"));
        assertEquals(Arrays.asList("电感","电容","电阻"),InventoryBrowser.categories(names,counts,Collections.singleton("电感"),Collections.emptyMap(),0,true,"全部"));
    }
    @Test public void recentCategoryOrderingIsExplicit() {
        Map<String,Long> visits=new HashMap<>();visits.put("电阻",100L);visits.put("电容",200L);
        assertEquals("电容",InventoryBrowser.categories(Arrays.asList("电阻","电容"),Collections.emptyMap(),Collections.emptySet(),visits,2,false,"全部").get(0));
    }
    @Test public void selectedEmptyCategoryIsNotHidden() {
        assertEquals(Collections.singletonList("电阻"),InventoryBrowser.categories(Arrays.asList("电阻","电容"),Collections.emptyMap(),Collections.emptySet(),Collections.emptyMap(),0,true,"电阻"));
    }
    @Test public void combinedFiltersRequireEveryCondition() {
        Part a=part(1,"C12345","电阻",3,"10 kΩ"),b=part(2,"C67890","电阻",20,"10 kΩ");a.color="红色";b.color="蓝色";a.location="A1";b.location="B1";
        InventoryBrowser.Filter f=new InventoryBrowser.Filter();f.category="电阻";f.footprint="0603";f.color="红色";f.location="A1";f.stock=3;
        assertEquals(Collections.singletonList(a),InventoryBrowser.apply(Arrays.asList(a,b),f));f.location="B1";assertTrue(InventoryBrowser.apply(Arrays.asList(a,b),f).isEmpty());
    }
    @Test public void searchCombinesTokensAndEquivalentUnits() {
        Part p=part(1,"C12345","电容",5,"");p.capacitance="100 nF";p.footprint="0402";
        InventoryBrowser.Filter f=new InventoryBrowser.Filter();f.query="0.1uF 0402";
        assertEquals(1,InventoryBrowser.apply(Collections.singletonList(p),f).size());f.query="0.1uF 0603";assertTrue(InventoryBrowser.apply(Collections.singletonList(p),f).isEmpty());
    }
    @Test public void resistanceSortingUsesPhysicalValuesNotText() {
        Part a=part(1,"A","电阻",1,"10 kΩ"),b=part(2,"B","电阻",1,"100 Ω"),c=part(3,"C","电阻",1,"1 MΩ");
        InventoryBrowser.Filter f=new InventoryBrowser.Filter();f.sort=4;
        assertEquals(Arrays.asList(b,a,c),InventoryBrowser.apply(Arrays.asList(c,a,b),f));
    }
    @Test public void emptyValuesSortLastAndDimensionsStaySeparate() {
        Part empty=part(1,"A","其他",1,""),c=part(2,"B","电容",1,"");c.capacitance="1 µF";
        Part r=part(3,"C","电阻",1,"1 Ω");InventoryBrowser.Filter f=new InventoryBrowser.Filter();f.sort=4;
        assertEquals(Arrays.asList(r,c,empty),InventoryBrowser.apply(Arrays.asList(empty,c,r),f));
    }
    @Test public void stockSortAndZeroFiltersDoNotAlterInventory() {
        Part a=part(1,"A","电阻",0,"1 Ω"),b=part(2,"B","电阻",20,"1 Ω");InventoryBrowser.Filter f=new InventoryBrowser.Filter();f.stock=2;
        assertEquals(Collections.singletonList(a),InventoryBrowser.apply(Arrays.asList(a,b),f));f.stock=0;f.sort=2;
        assertEquals(Arrays.asList(b,a),InventoryBrowser.apply(Arrays.asList(a,b),f));assertEquals(20,b.quantity);
    }
}

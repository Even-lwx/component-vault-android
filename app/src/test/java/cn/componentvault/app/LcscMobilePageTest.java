package cn.componentvault.app;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** 固定样本来自手机 UA、阻塞图片请求（load 未完成）时的实际商品页提取结果。 */
public class LcscMobilePageTest {
    private Part read(String code) throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/lcsc/"+code+".txt")) {
            assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            return LcscProductParser.parse(new String(out.toByteArray(),StandardCharsets.UTF_8),code);
        }
    }
    @Test public void C17916Resistor() throws Exception {
        Part p=read("C17916");assertEquals("150 kΩ",p.resistance);assertEquals("250 mW",p.power);
        assertEquals("200 V",p.voltage);assertEquals("1206",p.footprint);assertEquals("1206W4F1503T5E",p.model);
    }
    @Test public void C422611DecimalAndMegaArePreserved() throws Exception {
        Part p=read("C422611");assertEquals("10.2 MΩ",p.resistance);assertEquals("250 mW",p.power);
        assertEquals("1206W4F1025T5E",p.model);assertEquals("200 V",p.voltage);
    }
    @Test public void C46634440MilliOhmsNotMegaOhms() throws Exception {
        Part p=read("C46634440");assertEquals("20 mΩ",p.resistance);assertEquals("1 W",p.power);
        assertEquals("JER1206F1R020",p.model);assertEquals("电流采样电阻/分流器",p.category);
    }
    @Test public void C28756VaristorAttributesRetainMeaning() throws Exception {
        Part p=read("C28756");assertEquals("压敏电阻",p.category);assertEquals("插件,P=5mm",p.footprint);
        assertEquals("",p.resistance);assertEquals("",p.voltage);assertEquals("",p.current);
        assertTrue(p.description.contains("压敏电压: 423V~517V"));assertTrue(p.description.contains("钳位电压: 775V"));
        assertTrue(p.description.contains("工作电压(DC): 385V"));assertTrue(p.description.contains("能量: 19.7J"));
        assertTrue(p.description.contains("峰值浪涌电流: 1.2kA"));
    }
}

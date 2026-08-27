package com.example.healthreport.parse;

import com.example.healthreport.parse.ofd.OfdParseResult;
import com.example.healthreport.parse.ofd.OfdSegmentParser;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** OFD 一个原子文本对象一个 segment 及左上原点换算测试。 */
class OfdSegmentParserTest {

    @Test
    void shouldKeepAtomicTextObjectsInSourceOrder() throws Exception {
        OfdSegmentParser parser = new OfdSegmentParser(new TextNormalizer(), new ZipBombGuard());

        OfdParseResult result = parser.parse(ofdWithTextObjects(), 1);

        assertThat(result.getPageCount()).isEqualTo(1);
        assertThat(result.getSegmentList()).extracting(Segment::getRawText)
                .containsExactly("AB", "C");
        assertThat(result.getSegmentList()).extracting(Segment::getSegmentId)
                .containsExactly("f1-p1-s0", "f1-p1-s1");
        assertBoxEqualsMillimetreRect(result.getSegmentList().get(0).getBbox(), 10D, 20D, 30D, 5D);
        assertBoxEqualsMillimetreRect(result.getSegmentList().get(1).getBbox(), 45D, 20D, 10D, 5D);
    }

    /**
     * OFD 的 CT_PageBlock 只是分组，继承自 OFDElement 而非 CT_GraphicUnit，
     * 既没有 Boundary 也没有 CTM，不建立新坐标系——嵌套多深，TextObject 的 Boundary
     * 都已经是页面坐标。本用例把同样的文字对象包进两层页块，断言 bbox 与平铺时逐位相等。
     * 【这条是防止有人误加父级偏移】：叠加不存在的容器变换会让坐标凭空平移。
     */
    @Test
    void nestedPageBlocksMustNotShiftCoordinates() throws Exception {
        OfdSegmentParser parser = new OfdSegmentParser(new TextNormalizer(), new ZipBombGuard());

        OfdParseResult nested = parser.parse(ofdWithNestedPageBlocks(), 1);

        assertThat(nested.getSegmentList()).extracting(Segment::getRawText).containsExactly("AB", "C");
        assertBoxEqualsMillimetreRect(nested.getSegmentList().get(0).getBbox(), 10D, 20D, 30D, 5D);
        assertBoxEqualsMillimetreRect(nested.getSegmentList().get(1).getBbox(), 45D, 20D, 10D, 5D);
    }

    /**
     * OFD 的 Boundary 用毫米，下游拿到的 bbox 用渲染图像素，两者靠 300DPI 换算：
     * 像素 = 毫米 × 300 ÷ 25.4。本用例的页面长边换算后未超过 3600px 的封顶值，
     * 所以没有额外的等比缩小，可以直接按这个系数断言。
     */
    private void assertBoxEqualsMillimetreRect(com.example.healthreport.parse.segment.BBox box,
                                               double xMillimetres, double yMillimetres,
                                               double widthMillimetres, double heightMillimetres) {
        double scale = 300D / 25.4D;
        assertThat(box).isNotNull();
        assertThat(box.getX()).isCloseTo(xMillimetres * scale, within(0.5D));
        assertThat(box.getY()).isCloseTo(yMillimetres * scale, within(0.5D));
        assertThat(box.getWidth()).isCloseTo(widthMillimetres * scale, within(0.5D));
        assertThat(box.getHeight()).isCloseTo(heightMillimetres * scale, within(0.5D));
    }

    private byte[] ofdWithTextObjects() throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "OFD.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<OFD xmlns=\"http://www.ofdspec.org/2016\"><DocBody><DocInfo>"
                    + "<DocID>synthetic-ofd</DocID></DocInfo><DocRoot>Doc_0/Document.xml</DocRoot>"
                    + "</DocBody></OFD>");
            add(zip, "Doc_0/Document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Document xmlns=\"http://www.ofdspec.org/2016\"><CommonData><MaxUnitID>20</MaxUnitID>"
                    + "<PageArea><PhysicalBox>0 0 210 297</PhysicalBox></PageArea></CommonData>"
                    + "<Pages><Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/></Pages></Document>");
            add(zip, "Doc_0/Pages/Page_0/Content.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Page xmlns=\"http://www.ofdspec.org/2016\"><Area><PhysicalBox>0 0 210 297</PhysicalBox>"
                    + "</Area><Content><Layer ID=\"10\"><TextObject ID=\"11\" Boundary=\"10 20 30 5\" "
                    + "Font=\"1\" Size=\"3\"><TextCode X=\"0\" Y=\"3\">A</TextCode>"
                    + "<TextCode X=\"5\" Y=\"3\">B</TextCode></TextObject>"
                    + "<TextObject ID=\"12\" Boundary=\"45 20 10 5\" Font=\"1\" Size=\"3\">"
                    + "<TextCode X=\"0\" Y=\"3\">C</TextCode></TextObject></Layer></Content></Page>");
            zip.finish();
            return output.toByteArray();
        }
    }

    /** 与 ofdWithTextObjects 内容相同，但把两个文字对象包进两层 PageBlock。 */
    private byte[] ofdWithNestedPageBlocks() throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "OFD.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<OFD xmlns=\"http://www.ofdspec.org/2016\"><DocBody><DocInfo>"
                    + "<DocID>synthetic-ofd</DocID></DocInfo><DocRoot>Doc_0/Document.xml</DocRoot>"
                    + "</DocBody></OFD>");
            add(zip, "Doc_0/Document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Document xmlns=\"http://www.ofdspec.org/2016\"><CommonData><MaxUnitID>20</MaxUnitID>"
                    + "<PageArea><PhysicalBox>0 0 210 297</PhysicalBox></PageArea></CommonData>"
                    + "<Pages><Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/></Pages></Document>");
            add(zip, "Doc_0/Pages/Page_0/Content.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Page xmlns=\"http://www.ofdspec.org/2016\"><Area><PhysicalBox>0 0 210 297</PhysicalBox>"
                    + "</Area><Content><Layer ID=\"10\"><PageBlock ID=\"90\"><PageBlock ID=\"91\">"
                    + "<TextObject ID=\"11\" Boundary=\"10 20 30 5\" "
                    + "Font=\"1\" Size=\"3\"><TextCode X=\"0\" Y=\"3\">A</TextCode>"
                    + "<TextCode X=\"5\" Y=\"3\">B</TextCode></TextObject>"
                    + "<TextObject ID=\"12\" Boundary=\"45 20 10 5\" Font=\"1\" Size=\"3\">"
                    + "<TextCode X=\"0\" Y=\"3\">C</TextCode></TextObject>"
                    + "</PageBlock></PageBlock></Layer></Content></Page>");
            zip.finish();
            return output.toByteArray();
        }
    }

    private void add(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

package com.example.healthreport.parse.pdf;

import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.GlyphDensityGate;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizationResult;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.contentstream.operator.text.BeginText;
import org.apache.pdfbox.contentstream.operator.text.EndText;
import org.apache.pdfbox.contentstream.operator.text.MoveText;
import org.apache.pdfbox.contentstream.operator.text.MoveTextSetLeading;
import org.apache.pdfbox.contentstream.operator.text.NextLine;
import org.apache.pdfbox.contentstream.operator.text.SetCharSpacing;
import org.apache.pdfbox.contentstream.operator.text.SetFontAndSize;
import org.apache.pdfbox.contentstream.operator.text.SetTextHorizontalScaling;
import org.apache.pdfbox.contentstream.operator.text.SetTextLeading;
import org.apache.pdfbox.contentstream.operator.text.SetTextRenderingMode;
import org.apache.pdfbox.contentstream.operator.text.SetTextRise;
import org.apache.pdfbox.contentstream.operator.text.SetWordSpacing;
import org.apache.pdfbox.contentstream.operator.text.ShowText;
import org.apache.pdfbox.contentstream.operator.text.ShowTextAdjusted;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLine;
import org.apache.pdfbox.contentstream.operator.text.ShowTextLineAndSpace;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PDF 内容流原生文本绘制单元解析器。
 * <p>一个 Tj/TJ 操作严格对应一个 segment；不聚类、不拼行、不识别表格。</p>
 */
@Component
public class PdfSegmentParser {

    public static final int RENDER_DPI = 300;
    public static final int MAX_RENDER_LONG_EDGE = 3600;

    private final TextNormalizer textNormalizer;
    private final GlyphDensityGate glyphDensityGate;

    public PdfSegmentParser(TextNormalizer textNormalizer, GlyphDensityGate glyphDensityGate) {
        this.textNormalizer = textNormalizer;
        this.glyphDensityGate = glyphDensityGate;
    }

    /** 解析全部页面；密度闸命中时丢弃原生块并要求调用方整文件改走 OCR。 */
    public PdfParseResult parse(PDDocument document, int fileIndex) throws IOException {
        if (document == null || document.getNumberOfPages() <= 0) {
            throw new IllegalArgumentException("PDF 文档必须至少包含一页");
        }
        List<Segment> segmentList = new ArrayList<Segment>();
        int[] sequence = new int[]{0};
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            PageGeometry geometry = PageGeometry.from(page);
            DrawingUnitEngine engine = new DrawingUnitEngine(fileIndex, pageNumber, sequence,
                    geometry, textNormalizer);
            engine.processPage(page);
            segmentList.addAll(engine.getSegmentList());
        }
        if (glyphDensityGate.requiresOcr(segmentList.size(), document.getNumberOfPages())) {
            return new PdfParseResult(Collections.<Segment>emptyList(), true,
                    document.getNumberOfPages());
        }
        return new PdfParseResult(segmentList, false, document.getNumberOfPages());
    }

    /** 仅注册处理内容流文本与必要图形状态的操作符。 */
    private static final class DrawingUnitEngine extends PDFStreamEngine {

        private final int fileIndex;
        private final int pageNumber;
        private final int[] sequence;
        private final PageGeometry geometry;
        private final TextNormalizer textNormalizer;
        private final List<Segment> segmentList = new ArrayList<Segment>();
        private DrawingUnit currentUnit;
        /** 绘制单元嵌套深度；Type 3 字形内容流会让 Tj 递归，>0 表示身处内层。 */
        private int unitDepth;

        private DrawingUnitEngine(int fileIndex, int pageNumber, int[] sequence,
                                  PageGeometry geometry, TextNormalizer textNormalizer) {
            this.fileIndex = fileIndex;
            this.pageNumber = pageNumber;
            this.sequence = sequence;
            this.geometry = geometry;
            this.textNormalizer = textNormalizer;
            registerOperators();
        }

        @Override
        public void showTextString(byte[] string) throws IOException {
            beginUnit();
            try {
                super.showTextString(string);
            } finally {
                finishUnit();
            }
        }

        @Override
        public void showTextStrings(COSArray array) throws IOException {
            beginUnit();
            try {
                super.showTextStrings(array);
            } finally {
                finishUnit();
            }
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                 String unicode, Vector displacement) throws IOException {
            super.showGlyph(textRenderingMatrix, font, code, unicode, displacement);
            if (currentUnit == null || unicode == null) {
                return;
            }
            currentUnit.append(unicode, textRenderingMatrix, displacement);
        }

        /**
         * 开始一个绘制单元。
         * 【允许嵌套】Type 3 字体的字形过程本身就是内容流，其中可以合法地再次出现 Tj/TJ，
         * 递归回到本方法。嵌套时只保留最外层单元，内层字形照常累加进外层——
         * 它们本来就是在渲染外层那次绘制操作的字形。
         * 早先这里抛 IllegalStateException，会让含 Type 3 字体的 PDF 整份失败，
         * 且因是非业务异常而落 SERVER_ERROR、被误标为可重试。
         */
        private void beginUnit() {
            unitDepth++;
            if (currentUnit == null) {
                currentUnit = new DrawingUnit();
            }
        }

        /** 结束一个绘制单元；只有回到最外层时才真正产出 segment。 */
        private void finishUnit() {
            unitDepth--;
            if (unitDepth > 0) {
                return;
            }
            unitDepth = 0;
            DrawingUnit finishedUnit = currentUnit;
            currentUnit = null;
            if (finishedUnit == null || finishedUnit.rawTextBuilder.length() == 0) {
                return;
            }
            String rawText = finishedUnit.rawTextBuilder.toString();
            TextNormalizationResult normalizationResult = textNormalizer.normalize(rawText);
            segmentList.add(new Segment(Segment.id(fileIndex, pageNumber, sequence[0]++),
                    rawText, normalizationResult.getNormalizedText(), TextSource.NATIVE,
                    geometry.toRenderedBox(finishedUnit.toUserBox())));
        }

        private void registerOperators() {
            addOperator(new Concatenate());
            addOperator(new DrawObject());
            addOperator(new SetGraphicsStateParameters());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
            addOperator(new BeginText());
            addOperator(new EndText());
            addOperator(new SetCharSpacing());
            addOperator(new SetFontAndSize());
            addOperator(new SetTextHorizontalScaling());
            addOperator(new SetTextLeading());
            addOperator(new SetTextRenderingMode());
            addOperator(new SetTextRise());
            addOperator(new SetWordSpacing());
            addOperator(new ShowText());
            addOperator(new ShowTextAdjusted());
            addOperator(new ShowTextLine());
            addOperator(new ShowTextLineAndSpace());
            addOperator(new MoveText());
            addOperator(new MoveTextSetLeading());
            addOperator(new NextLine());
        }

        private List<Segment> getSegmentList() {
            return segmentList;
        }

    }

    /** 单次绘制操作内的字形文本与用户空间外包框。 */
    private static final class DrawingUnit {

        private final StringBuilder rawTextBuilder = new StringBuilder();
        private double minX = Double.MAX_VALUE;
        private double minY = Double.MAX_VALUE;
        private double maxX = -Double.MAX_VALUE;
        private double maxY = -Double.MAX_VALUE;

        private void append(String unicode, Matrix matrix, Vector displacement) {
            rawTextBuilder.append(unicode);
            include(matrix.transformPoint(0F, 0F));
            include(matrix.transformPoint(displacement.getX(), displacement.getY()));
            include(matrix.transformPoint(0F, 1F));
            include(matrix.transformPoint(displacement.getX(), 1F));
        }

        private void include(Point2D point) {
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
        }

        private UserBox toUserBox() {
            if (minX == Double.MAX_VALUE) {
                return new UserBox(0D, 0D, 0D, 0D);
            }
            return new UserBox(minX, minY, maxX - minX, maxY - minY);
        }
    }

    /** 页面旋转归一化与 PDF 左下原点到渲染图左上原点的唯一转换点。 */
    private static final class PageGeometry {

        private final PDRectangle cropBox;
        private final int rotation;
        private final double scale;
        private final double baseWidth;
        private final double baseHeight;

        private PageGeometry(PDRectangle cropBox, int rotation, double scale) {
            this.cropBox = cropBox;
            this.rotation = rotation;
            this.scale = scale;
            this.baseWidth = cropBox.getWidth() * scale;
            this.baseHeight = cropBox.getHeight() * scale;
        }

        private static PageGeometry from(PDPage page) {
            PDRectangle cropBox = page.getCropBox();
            int rotation = normalizeRotation(page.getRotation());
            double dpiScale = (double) RENDER_DPI / 72D * page.getUserUnit();
            double rotatedWidth = (rotation == 90 || rotation == 270)
                    ? cropBox.getHeight() * dpiScale : cropBox.getWidth() * dpiScale;
            double rotatedHeight = (rotation == 90 || rotation == 270)
                    ? cropBox.getWidth() * dpiScale : cropBox.getHeight() * dpiScale;
            double capScale = Math.max(rotatedWidth, rotatedHeight) > MAX_RENDER_LONG_EDGE
                    ? (double) MAX_RENDER_LONG_EDGE / Math.max(rotatedWidth, rotatedHeight) : 1D;
            return new PageGeometry(cropBox, rotation, dpiScale * capScale);
        }

        private static int normalizeRotation(int rotation) {
            int normalized = rotation % 360;
            return normalized < 0 ? normalized + 360 : normalized;
        }

        private BBox toRenderedBox(UserBox userBox) {
            double x = (userBox.x - cropBox.getLowerLeftX()) * scale;
            double y = (cropBox.getUpperRightY() - userBox.y - userBox.height) * scale;
            double width = userBox.width * scale;
            double height = userBox.height * scale;
            BBox unrotated = safeBox(x, y, width, height, baseWidth, baseHeight);
            switch (rotation) {
                case 90:
                    return safeBox(baseHeight - unrotated.getY() - unrotated.getHeight(),
                            unrotated.getX(), unrotated.getHeight(), unrotated.getWidth(),
                            baseHeight, baseWidth);
                case 180:
                    return safeBox(baseWidth - unrotated.getX() - unrotated.getWidth(),
                            baseHeight - unrotated.getY() - unrotated.getHeight(),
                            unrotated.getWidth(), unrotated.getHeight(), baseWidth, baseHeight);
                case 270:
                    return safeBox(unrotated.getY(), baseWidth - unrotated.getX() - unrotated.getWidth(),
                            unrotated.getHeight(), unrotated.getWidth(), baseHeight, baseWidth);
                default:
                    return unrotated;
            }
        }

        private BBox safeBox(double x, double y, double width, double height,
                             double imageWidth, double imageHeight) {
            double safeX = Math.max(0D, Math.min(x, imageWidth));
            double safeY = Math.max(0D, Math.min(y, imageHeight));
            double safeWidth = Math.max(0D, Math.min(width, imageWidth - safeX));
            double safeHeight = Math.max(0D, Math.min(height, imageHeight - safeY));
            return new BBox(safeX, safeY, safeWidth, safeHeight);
        }
    }

    private static final class UserBox {
        private final double x;
        private final double y;
        private final double width;
        private final double height;

        private UserBox(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}

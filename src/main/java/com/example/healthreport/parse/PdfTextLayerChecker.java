package com.example.healthreport.parse;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PDF 原生文本层可用性判定。
 * <p>仅逐字形计数，不聚行、不排序、不推断表格；因此不使用会内部聚行的 PDFTextStripper。</p>
 */
@Component
public class PdfTextLayerChecker {

    static final int MIN_CHARACTERS_PER_PAGE = 50;
    static final double MIN_NON_WHITESPACE_RATIO = 0.30D;

    /**
     * 判断是否可走原生文本层；任一阈值不满足时应改走 OCR。
     */
    public boolean hasUsableTextLayer(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        if (pageCount < 1) {
            return false;
        }
        TextCharacterCounter counter = new TextCharacterCounter();
        for (PDPage page : document.getPages()) {
            counter.processPage(page);
        }
        double charactersPerPage = (double) counter.getCharacterCount() / (double) pageCount;
        if (charactersPerPage < MIN_CHARACTERS_PER_PAGE || counter.getCharacterCount() == 0L) {
            return false;
        }
        double nonWhitespaceRatio = (double) counter.getNonWhitespaceCount()
                / (double) counter.getCharacterCount();
        return nonWhitespaceRatio >= MIN_NON_WHITESPACE_RATIO;
    }

    /**
     * 只注册读取文字绘制操作所需的 PDF 操作符，并在 showGlyph 中计数。
     */
    private static class TextCharacterCounter extends PDFStreamEngine {

        private long characterCount;
        private long nonWhitespaceCount;

        private TextCharacterCounter() {
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
            addOperator(new MoveText());
            addOperator(new MoveTextSetLeading());
            addOperator(new NextLine());
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                 String unicode, Vector displacement) throws IOException {
            super.showGlyph(textRenderingMatrix, font, code, unicode, displacement);
            if (unicode == null) {
                return;
            }
            for (int index = 0; index < unicode.length(); index++) {
                char current = unicode.charAt(index);
                characterCount++;
                if (!Character.isWhitespace(current)) {
                    nonWhitespaceCount++;
                }
            }
        }

        private long getCharacterCount() {
            return characterCount;
        }

        private long getNonWhitespaceCount() {
            return nonWhitespaceCount;
        }
    }
}

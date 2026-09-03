package com.example.healthreport.parse;

import com.example.healthreport.parse.segment.BBox;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bbox 必须从原始渲染图坐标系换算到发给 LLM-A 的压缩图坐标系。
 *
 * <p>{@code PdfSegmentParser} 产出的 bbox 钳制在渲染图尺寸上，而
 * {@code ExtractionImageCompressor} 会把图缩到长边 2000（超限时回退 1600）再发出去。
 * 不换算的话模型拿到的坐标和它看到的图<b>整体错位</b>，而 bbox 正是它判断行列归属的依据。</p>
 *
 * <p><b>系数必须取压缩图的实际宽高</b>，不是配置里的 2000——回退到 1600 时用配置值会再错一次。</p>
 */
class BBoxScalingContractTest {

    @Test
    void bboxMustScaleFromRenderedToCompressedDimensions() {
        BBox rendered = new BBox(100D, 200D, 50D, 20D);

        BBox scaled = rendered.scale(2480, 3508, 1414, 2000);

        double scaleX = 1414D / 2480D;
        double scaleY = 2000D / 3508D;
        assertThat(scaled.getX()).isEqualTo(100D * scaleX);
        assertThat(scaled.getY()).isEqualTo(200D * scaleY);
        assertThat(scaled.getWidth()).isEqualTo(50D * scaleX);
        assertThat(scaled.getHeight()).isEqualTo(20D * scaleY);
    }

    /** 回退档 1600 与主档 2000 的系数必须不同——用配置常量算就会错。 */
    @Test
    void fallbackLongEdgeMustProduceDifferentCoefficients() {
        BBox rendered = new BBox(0D, 1000D, 10D, 10D);

        BBox primary = rendered.scale(2480, 3508, 1414, 2000);
        BBox fallback = rendered.scale(2480, 3508, 1131, 1600);

        assertThat(primary.getY()).isNotEqualTo(fallback.getY());
    }

    /** 渲染图与压缩图同尺寸时不做换算，坐标原样保留。 */
    @Test
    void identityScaleMustKeepCoordinates() {
        BBox rendered = new BBox(12D, 34D, 56D, 78D);

        BBox scaled = rendered.scale(1000, 800, 1000, 800);

        assertThat(scaled.getX()).isEqualTo(12D);
        assertThat(scaled.getHeight()).isEqualTo(78D);
    }
}

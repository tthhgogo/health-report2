package com.example.healthreport.render.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 渲染前的影像剔除：发给 LLM-A 的页面图不需要报告里的医学影像
 * （超声、CT 截图等），文字版报告页上的图片在渲染前全部移除。
 *
 * <p><b>扫描版保护是第一优先级</b>，误删代价不对称——把扫描报告页清空是数据丢失，
 * 漏删只是影像留在页面里（即改造前的现状）。因此仅当一页同时满足两条才动它：</p>
 * <ul>
 * <li>页内容流的文字显示算子（Tj/TJ/'/"）携带的字符串字节量达到
 * {@link #MIN_TEXT_SHOW_BYTES}——无文字的页按纯扫描页保留；</li>
 * <li>页上没有任何「整页扫描量级」的大图：图片像素面积 ≥ 页面面积 ×
 * {@link #SCAN_CANDIDATE_DPI}² 时，它可能就是带 OCR 文本层的双层扫描件的底图，
 * 整页保留。超声/CT 缩略图远小于该阈值，不受影响；</li>
 * <li>页内图片的实际绘制覆盖率（内容流 CTM 累计，XObject 与 BI…EI 内联图片都计入）
 * 低于 {@link #SCAN_COVERAGE_RATIO}——拆成条带的分块扫描件每条都不大，但画满整页，
 * 这条判据兜住它；漏算内联图片会低估覆盖率、误删同页的 XObject 条带。</li>
 * </ul>
 *
 * <p>资源树递归受 {@link #MAX_FORM_NESTING_DEPTH} 深度预算约束：超限按 IOException
 * 中止，该页保留原样——栈溢出是 Error，页级兜底接不住。</p>
 *
 * <p><b>资源可能被多页共享</b>：剔除绝不原位修改资源字典，而是把 Resources、XObject
 * 子字典与嵌套 Form 沿路克隆后只改克隆——否则处理文字页会连带清空共享同一资源的
 * 受保护扫描页。</p>
 *
 * <p>剔除用空 Form XObject 原位替换图片对象，内容流里的 Do 算子照常执行但画不出
 * 任何东西，不会因资源缺失渲染失败。图片对象嵌在 Form XObject 里的同样处理。
 * 文字信号来自内容流 token 的机械计数，不抽取文本、无坐标无聚类
 * （ParsingArchitectureTest 红线：render 包不得依赖 PDFTextStripper）。</p>
 *
 * <p>单页处理失败只记告警并保留该页原样：剔除是输入净化，不是可读性门槛，
 * 坏页该不该失败由渲染阶段裁决。</p>
 */
@Slf4j
@Component
public class PdfImageStripper {

	/** 文字判据：约 25 个 CJK 字（双字节 CID）或 50 个 ASCII 字符，低于此按扫描页保留。 */
	static final int MIN_TEXT_SHOW_BYTES = 50;

	/** 整页扫描候选 DPI：家用扫描仪最低档也在 100 DPI 以上，低于它的图不可能是整页底图。 */
	static final int SCAN_CANDIDATE_DPI = 100;

	/**
	 * 绘制覆盖率保护线：页内图片实际绘制面积占页面积达到该比例即整页保留。
	 * 挡住被拆成条带的分块扫描件（每条单独看都不大，画满整页的事实不变）；
	 * 超声/CT 缩略图页的覆盖率通常在两成上下，远够不到。
	 */
	static final double SCAN_COVERAGE_RATIO = 0.5D;

	/**
	 * Form 资源嵌套深度预算：正常文档两三层封顶，超限视为异常构造。递归防环挡不住
	 * 长链嵌套，栈溢出是 Error，页级兜底接不住，必须在发生前按 IOException 中止，
	 * 让该页走「按原样渲染」路径。
	 */
	static final int MAX_FORM_NESTING_DEPTH = 30;

	/** 对整份文档逐页剔除影像；只改内存中的文档对象，不产生任何落盘。 */
	public void stripImages(PDDocument document) {
		int pageIndex = 0;
		for (PDPage page : document.getPages()) {
			try {
				stripPage(document, page);
			}
			catch (IOException | RuntimeException exception) {
				log.warn("PDF 第{}页影像剔除失败，该页按原样渲染", pageIndex + 1, exception);
			}
			pageIndex++;
		}
	}

	private void stripPage(PDDocument document, PDPage page) throws IOException {
		PDResources resources = page.getResources();
		if (resources == null) {
			return;
		}
		if (countTextShowBytes(page) < MIN_TEXT_SHOW_BYTES) {
			return;
		}
		PDRectangle cropBox = page.getCropBox();
		if (cropBox == null || cropBox.getWidth() <= 0F || cropBox.getHeight() <= 0F) {
			return;
		}
		double pageAreaSquareInches =
				(cropBox.getWidth() / 72D) * (cropBox.getHeight() / 72D);
		double scanPixelAreaThreshold =
				pageAreaSquareInches * SCAN_CANDIDATE_DPI * SCAN_CANDIDATE_DPI;
		if (containsScanScaleImage(resources, scanPixelAreaThreshold, new HashSet<COSBase>(), 0)) {
			return;
		}
		// 分块扫描件保护：单张面积判据挡不住被拆成条带的整页扫描（每条都低于阈值），
		// 按内容流实际绘制面积累计覆盖率兜住——条带无论怎么拆，画满整页的事实不变。
		ImageCoverageScanner coverageScanner = new ImageCoverageScanner();
		coverageScanner.processPage(page);
		double pageAreaPoints = (double) cropBox.getWidth() * cropBox.getHeight();
		if (coverageScanner.getDrawnImageAreaPoints() >= pageAreaPoints * SCAN_COVERAGE_RATIO) {
			return;
		}
		// 资源可能被多页共享：绝不原位修改，沿路克隆（Resources → XObject 子字典 → Form 流）
		// 后只替换克隆里的图片条目，受保护页引用的原始对象保持原样。
		page.setResources(cloneResourcesWithImagesStripped(document, resources,
				new HashMap<COSBase, PDFormXObject>(), 0));
	}

	/** 数内容流里文字显示算子携带的字符串字节量；只计数，不还原文本。 */
	private long countTextShowBytes(PDPage page) throws IOException {
		PDFStreamParser parser = new PDFStreamParser(page);
		parser.parse();
		long shownBytes = 0;
		List<COSBase> operandList = new ArrayList<COSBase>();
		for (Object token : parser.getTokens()) {
			if (!(token instanceof Operator)) {
				if (token instanceof COSBase) {
					operandList.add((COSBase) token);
				}
				continue;
			}
			String operatorName = ((Operator) token).getName();
			if ("Tj".equals(operatorName) || "'".equals(operatorName)
					|| "\"".equals(operatorName)) {
				for (COSBase operand : operandList) {
					if (operand instanceof COSString) {
						shownBytes += ((COSString) operand).getBytes().length;
					}
				}
			}
			else if ("TJ".equals(operatorName)) {
				for (COSBase operand : operandList) {
					if (operand instanceof COSArray) {
						for (COSBase element : (COSArray) operand) {
							if (element instanceof COSString) {
								shownBytes += ((COSString) element).getBytes().length;
							}
						}
					}
				}
			}
			operandList.clear();
		}
		return shownBytes;
	}

	/** 递归检查资源树里是否存在整页扫描量级的图片；visited 防环，深度预算防长链栈溢出。 */
	private boolean containsScanScaleImage(PDResources resources, double scanPixelAreaThreshold,
			Set<COSBase> visitedFormStreams, int nestingDepth) throws IOException {
		if (nestingDepth > MAX_FORM_NESTING_DEPTH) {
			throw new IOException("Form 资源嵌套超过深度预算 " + MAX_FORM_NESTING_DEPTH);
		}
		for (COSName xObjectName : resources.getXObjectNames()) {
			if (resources.isImageXObject(xObjectName)) {
				PDImageXObject image = (PDImageXObject) resources.getXObject(xObjectName);
				long pixelArea = (long) image.getWidth() * image.getHeight();
				if (pixelArea >= scanPixelAreaThreshold) {
					return true;
				}
			}
			else {
				Object xObject = resources.getXObject(xObjectName);
				if (xObject instanceof PDFormXObject) {
					PDFormXObject form = (PDFormXObject) xObject;
					if (!visitedFormStreams.add(form.getCOSObject())) {
						continue;
					}
					PDResources formResources = form.getResources();
					if (formResources != null && containsScanScaleImage(formResources,
							scanPixelAreaThreshold, visitedFormStreams, nestingDepth + 1)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 克隆资源树并在克隆里把图片替换为空 Form XObject；原始对象一律不动。
	 * <p>Resources 字典、XObject 子字典与嵌套 Form 流都可能被其他页共享，原位修改会把
	 * 已通过保护检查的扫描页一并清空（实测整页变白），所以必须整条路径克隆。</p>
	 */
	private PDResources cloneResourcesWithImagesStripped(PDDocument document,
			PDResources originalResources, Map<COSBase, PDFormXObject> cloneByOriginalForm,
			int nestingDepth) throws IOException {
		if (nestingDepth > MAX_FORM_NESTING_DEPTH) {
			throw new IOException("Form 资源嵌套超过深度预算 " + MAX_FORM_NESTING_DEPTH);
		}
		COSDictionary clonedResourcesDict = new COSDictionary(originalResources.getCOSObject());
		PDResources clonedResources = new PDResources(clonedResourcesDict);
		COSBase xObjectBase = originalResources.getCOSObject()
				.getDictionaryObject(COSName.XOBJECT);
		if (!(xObjectBase instanceof COSDictionary)) {
			return clonedResources;
		}
		COSDictionary clonedXObjectDict = new COSDictionary((COSDictionary) xObjectBase);
		clonedResourcesDict.setItem(COSName.XOBJECT, clonedXObjectDict);
		for (COSName xObjectName : originalResources.getXObjectNames()) {
			if (originalResources.isImageXObject(xObjectName)) {
				PDFormXObject emptyForm = new PDFormXObject(new PDStream(document));
				emptyForm.setBBox(new PDRectangle(1F, 1F));
				clonedXObjectDict.setItem(xObjectName, emptyForm.getCOSObject());
			}
			else {
				Object xObject = originalResources.getXObject(xObjectName);
				if (xObject instanceof PDFormXObject) {
					PDFormXObject clonedForm = cloneFormWithImagesStripped(document,
							(PDFormXObject) xObject, cloneByOriginalForm, nestingDepth + 1);
					clonedXObjectDict.setItem(xObjectName, clonedForm.getCOSObject());
				}
			}
		}
		return clonedResources;
	}

	/** 克隆 Form 流（内容字节 + 字典项），其资源树递归走同样的克隆剔除；同一原始 Form 只克隆一次。 */
	private PDFormXObject cloneFormWithImagesStripped(PDDocument document,
			PDFormXObject originalForm, Map<COSBase, PDFormXObject> cloneByOriginalForm,
			int nestingDepth) throws IOException {
		PDFormXObject alreadyCloned = cloneByOriginalForm.get(originalForm.getCOSObject());
		if (alreadyCloned != null) {
			return alreadyCloned;
		}
		PDStream clonedStream = new PDStream(document, originalForm.getCOSObject().createInputStream());
		COSDictionary clonedFormDict = clonedStream.getCOSObject();
		for (Map.Entry<COSName, COSBase> entry : originalForm.getCOSObject().entrySet()) {
			COSName key = entry.getKey();
			if (COSName.LENGTH.equals(key) || COSName.FILTER.equals(key)
					|| COSName.RESOURCES.equals(key)) {
				continue;
			}
			clonedFormDict.setItem(key, entry.getValue());
		}
		PDFormXObject clonedForm = new PDFormXObject(clonedStream);
		cloneByOriginalForm.put(originalForm.getCOSObject(), clonedForm);
		PDResources originalFormResources = originalForm.getResources();
		if (originalFormResources != null) {
			clonedForm.setResources(cloneResourcesWithImagesStripped(document,
					originalFormResources, cloneByOriginalForm, nestingDepth + 1));
		}
		return clonedForm;
	}

	/** 累计内容流里图片的实际绘制面积（CTM 行列式的绝对值，单位 pt²）；只测量，不解码图片。 */
	private static final class ImageCoverageScanner
			extends org.apache.pdfbox.contentstream.PDFStreamEngine {

		private double drawnImageAreaPoints;

		ImageCoverageScanner() {
			addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate());
			addOperator(new org.apache.pdfbox.contentstream.operator.DrawObject());
			addOperator(new org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters());
			addOperator(new org.apache.pdfbox.contentstream.operator.state.Save());
			addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore());
			addOperator(new org.apache.pdfbox.contentstream.operator.state.SetMatrix());
		}

		double getDrawnImageAreaPoints() {
			return drawnImageAreaPoints;
		}

		@Override
		protected void processOperator(Operator operator, List<COSBase> operandList)
				throws IOException {
			if ("BI".equals(operator.getName())) {
				// 内联图片（BI…ID…EI）同样按 CTM 面积计入覆盖率：分块扫描件可以用
				// 内联图片拼页，漏算会低估覆盖率、误删同页的 XObject 条带。
				drawnImageAreaPoints += Math.abs(currentTransformDeterminant());
				return;
			}
			if ("Do".equals(operator.getName()) && !operandList.isEmpty()
					&& operandList.get(0) instanceof COSName) {
				COSName xObjectName = (COSName) operandList.get(0);
				if (getResources() != null && getResources().isImageXObject(xObjectName)) {
					drawnImageAreaPoints += Math.abs(currentTransformDeterminant());
					return;
				}
			}
			// 非图片 Do（含 Form）交回引擎：Form 会带着正确的 CTM 展开，里面的图片同样被计入。
			super.processOperator(operator, operandList);
		}

		/** 当前 CTM 的行列式 = 单位正方形经变换后的实际绘制面积（pt²）。 */
		private double currentTransformDeterminant() {
			org.apache.pdfbox.util.Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
			return (double) ctm.getValue(0, 0) * ctm.getValue(1, 1)
					- (double) ctm.getValue(0, 1) * ctm.getValue(1, 0);
		}

	}

}

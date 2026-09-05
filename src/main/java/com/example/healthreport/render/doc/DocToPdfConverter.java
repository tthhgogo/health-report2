package com.example.healthreport.render.doc;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToFoConverter;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 老版二进制 DOC 排版转 PDF（POI HWPF → {@code WordToFoConverter} → XSL-FO → Apache FOP），
 * 是 DOC 接入图像链路的唯一入口（设计方案 §3.2.1，2026-09-05 恢复 DOC 支持）。
 *
 * <p><b>页数以本转换结果为准</b>：与 DOCX 同一口径——对同一份字节、同一字体环境的排版
 * 是确定性的，上传预检与 Worker 复核各转一次页数必然一致（§3.4.1 契约保持）。</p>
 *
 * <p><b>图片一律丢弃</b>：与 DOCX 同一裁决（§3.2.1）。POI 的 {@code WordToFoConverter}
 * 在未设置 {@code PicturesManager} 时对图片的处理是空实现——图片元素根本不进 FO 树，
 * 内嵌图片不落盘、不产生临时文件，也不存在任何外链访问；本类绝不设置 PicturesManager。
 * 在此之上，{@link #sanitizeFoTree} 仍会把 FO 树里的 {@code fo:external-graphic} 与
 * {@code fo:instream-foreign-object} 连根移除，作为 POI 版本升级后行为变化的纵深防线。</p>
 *
 * <p><b>白字修复是必做的后处理</b>：{@code WordToFoConverter} 丢弃单元格底纹但保留字色，
 * 深色底纹表头（真实样本中的「小结」「初步意见」等）会变成白字白底整块隐形——这不是
 * 排版瑕疵而是内容丢失。后处理把白色文字统一改为正文深色 {@code #263238}。</p>
 *
 * <p><b>页眉页脚由本类自行并入</b>：{@code WordToFoConverter} 只排正文，页眉页脚会被
 * 静默丢掉，而那是报告信息（医院名、报告标题、体检人姓名等）。本类经
 * {@code HeaderStories} 按首页/奇数页/偶数页三档提取文字（「首页不同」的封面页眉与正文
 * 页眉常不一样，不能取一档应用到全页），注入 {@code fo:static-content} 并以条件页面主控
 * 按页型选择，PAGE 域转 {@code fo:page-number} 取真实页码
 * （见 {@link #appendHeaderFooterContent}）。</p>
 *
 * <p><b>POI 转换器日志被整包关断</b>：见 {@link #silencePoiConverterLogs}，防报告内容
 * 经第三方 WARN 日志外泄。</p>
 *
 * <p><b>字体环境只认内置思源黑体</b>：文档声明的全部字体在 FO 树里统一替换为内置字体
 * （{@code fonts/SourceHanSansCN-Regular.otf}，SIL OFL 1.1），并以文件 URI 直接注册进
 * FOP 配置——排版环境随代码走，跨机器分页一致。与 DOCX 链路不同，这里<b>没有</b>系统
 * 字体兜底：FOP 直接注册系统字体需要可靠的单字体文件路径，而 macOS/Linux 的 CJK 系统
 * 字体多为 .ttc 集合，FOP 对其解析不可靠；内置字体随 jar 分发、不存在「装没装」问题，
 * 加载失败只能是构建或部署损坏，按 SERVER_ERROR 失败——环境问题不得归因为用户文件
 * 不可读，更不能把中文渲染成 # 后静默送给模型。</p>
 */
@Slf4j
@Component
public class DocToPdfConverter {

	static {
		silencePoiConverterLogs();
	}

	/**
	 * POI 的 {@code AbstractWordConverter} 在遇到不支持的 Word 域时，WARN 日志会把域代码
	 * 与域内文字原样输出——那是报告内容，违反「报告内容不进普通日志」的白名单。
	 * 本应用的日志后端由 spring-boot-starter-logging 钉死为 logback，这里在类加载时把
	 * {@code org.apache.poi.hwpf.converter} 整包直接关断（该包只有转换器，没有必须保留的
	 * 运行告警；转换失败有本类自己的异常路径兜底）。instanceof 防御仅针对测试环境换后端
	 * 的极端情况，生产依赖树里恒为 logback。
	 */
	private static void silencePoiConverterLogs() {
		org.slf4j.Logger poiConverterLogger =
				org.slf4j.LoggerFactory.getLogger("org.apache.poi.hwpf.converter");
		if (poiConverterLogger instanceof ch.qos.logback.classic.Logger) {
			((ch.qos.logback.classic.Logger) poiConverterLogger)
					.setLevel(ch.qos.logback.classic.Level.OFF);
		}
	}

	/** 内置字体的 classpath 资源路径与注册进 FOP 的字族名（与 DOCX 链路同一份字体文件）。 */
	static final String BUNDLED_FONT_RESOURCE = "/fonts/SourceHanSansCN-Regular.otf";

	static final String BUNDLED_FONT_FAMILY = "Source Han Sans CN";

	/** 白字修复的目标色：正文深色，与实验期人工核对过的取值一致。 */
	static final String WHITE_TEXT_REPLACEMENT_COLOR = "#263238";

	private static final Object FOP_FACTORY_LOCK = new Object();

	private static volatile FopFactory fopFactory;

	/**
	 * 把一份老版二进制 DOC 排版转换为 PDF 字节，全程只在内存中进行
	 * （唯一的磁盘写入是首次转换时解压内置字体到临时文件，与文档内容无关）。
	 *
	 * @throws IOException 文档损坏、加密、Word 95 等 HWPF 无法解析的变体，或排版失败；
	 *             调用方按各自阶段映射为 FILE_UNREADABLE（上传）或 UNREADABLE（Worker）
	 * @throws HealthReportException SERVER_ERROR：内置字体资源加载失败（构建或部署损坏）
	 */
	public byte[] toPdf(byte[] docBytes) throws IOException {
		if (docBytes == null || docBytes.length == 0) {
			throw new IOException("DOC 内容为空");
		}
		// 字体环境解析放在文档解析之前也安全：内置字体不依赖文档内容；
		// 但按「坏文件优先报不可读」的口径，仍先解析文档再碰字体。
		final org.w3c.dom.Document foDocument;
		final HeaderFooterStory headerFooterStory;
		try (HWPFDocument hwpfDocument = new HWPFDocument(new ByteArrayInputStream(docBytes))) {
			// 不设置 PicturesManager：图片在转换源头即被丢弃（见类注释）。
			WordToFoConverter converter = new WordToFoConverter(
					DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
			converter.processDocument(hwpfDocument);
			foDocument = converter.getDocument();
			headerFooterStory = extractHeaderFooterStory(hwpfDocument);
		}
		catch (Exception exception) {
			// HWPF 对损坏文件抛 IOException、对加密文档与 Word 95 老变体抛运行时异常，统一收敛为不可读。
			throw new IOException("DOC 文档无法解析", exception);
		}
		appendHeaderFooterContent(foDocument, headerFooterStory);
		FoSanitizeStats stats = sanitizeFoTree(foDocument.getDocumentElement());
		if (stats.graphicsRemoved > 0) {
			log.warn("DOC 转换 FO 树中出现图形元素并已移除 {} 处——POI 无 PicturesManager 时不应产出图形，请核查 POI 版本行为",
					stats.graphicsRemoved);
		}
		if (stats.whiteTextFixes > 0) {
			log.info("DOC 转换白字修复 {} 处（深色底纹表头防隐形）", stats.whiteTextFixes);
		}
		if (stats.privateUseCharsReplaced > 0) {
			log.info("DOC 转换私用区符号替换为圆点 {} 处（Symbol/Wingdings 列表符号）",
					stats.privateUseCharsReplaced);
		}
		try {
			FopFactory factory = resolveFopFactory();
			FOUserAgent userAgent = factory.newFOUserAgent();
			ByteArrayOutputStream pdfStream = new ByteArrayOutputStream();
			Fop fop = factory.newFop(MimeConstants.MIME_PDF, userAgent, pdfStream);
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.transform(new DOMSource(foDocument), new SAXResult(fop.getDefaultHandler()));
			byte[] pdfBytes = pdfStream.toByteArray();
			if (pdfBytes.length == 0) {
				throw new IOException("DOC 排版转换产出为空");
			}
			return pdfBytes;
		}
		catch (IOException exception) {
			throw exception;
		}
		catch (HealthReportException exception) {
			throw exception;
		}
		catch (Exception exception) {
			// FOP 与 XSLT 的各类异常统一收敛为不可读（POI 产出的 FO 不保证严格合规）。
			throw new IOException("DOC 排版转换失败", exception);
		}
	}

	/** 供测试探测字体环境是否可用（口径与 DOCX 链路一致；内置字体缺失时相关用例跳过）。 */
	public static boolean fontEnvironmentAvailable() {
		try {
			resolveFopFactory();
			return true;
		}
		catch (HealthReportException exception) {
			return false;
		}
	}

	private static final String FO_NAMESPACE = "http://www.w3.org/1999/XSL/Format";

	/** 页眉/页脚段落里的 PAGE 域占位：注入 FO 时转成 {@code fo:page-number}，逐页取真实页码。 */
	private static final Object PAGE_NUMBER_PLACEHOLDER = new Object();

	/** 页眉/页脚的三种页型，与 Word 的「首页不同」「奇偶页不同」一一对应。 */
	static final String[] PAGE_VARIANTS = { "first", "odd", "even" };

	/**
	 * 从 .doc 提取出的页眉/页脚文字（{@code WordToFoConverter} 只排正文，这部分必须自取），
	 * 以及承载它们所需的页面边距。按首页/奇数页/偶数页三档分别保存<b>已回退解析</b>的内容
	 * （某档未单独设置时回退到普通档，与 POI {@code HeaderStories#getHeader(int)} 的
	 * 按非空文本选择语义一致）；每个段落是 String（文字）与 PAGE 占位的序列。
	 */
	static final class HeaderFooterStory {

		final List<List<Object>> firstHeader;

		final List<List<Object>> oddHeader;

		final List<List<Object>> evenHeader;

		final List<List<Object>> firstFooter;

		final List<List<Object>> oddFooter;

		final List<List<Object>> evenFooter;

		final double marginTopPoints;

		final double marginBottomPoints;

		HeaderFooterStory(List<List<Object>> firstHeader, List<List<Object>> oddHeader,
				List<List<Object>> evenHeader, List<List<Object>> firstFooter,
				List<List<Object>> oddFooter, List<List<Object>> evenFooter,
				double marginTopPoints, double marginBottomPoints) {
			this.firstHeader = firstHeader;
			this.oddHeader = oddHeader;
			this.evenHeader = evenHeader;
			this.firstFooter = firstFooter;
			this.oddFooter = oddFooter;
			this.evenFooter = evenFooter;
			this.marginTopPoints = marginTopPoints;
			this.marginBottomPoints = marginBottomPoints;
		}

		List<List<Object>> headerFor(String variant) {
			return "first".equals(variant) ? firstHeader
					: "even".equals(variant) ? evenHeader : oddHeader;
		}

		List<List<Object>> footerFor(String variant) {
			return "first".equals(variant) ? firstFooter
					: "even".equals(variant) ? evenFooter : oddFooter;
		}

		boolean isEmpty() {
			return firstHeader.isEmpty() && oddHeader.isEmpty() && evenHeader.isEmpty()
					&& firstFooter.isEmpty() && oddFooter.isEmpty() && evenFooter.isEmpty();
		}
	}

	/**
	 * 提取页眉/页脚文字，按首页/奇数页/偶数页三档分别保留——「首页不同」的封面页眉与
	 * 正文页眉常不一样，取任意一档应用到全页会丢内容。某档未单独设置（文本为空）时
	 * 回退到奇数页档，与 POI {@code HeaderStories#getHeader(int)} 的按非空文本选择语义
	 * 一致（.doc 的「启用但留空」与「未启用」在文本层不可区分，POI 同样如此处理）。
	 * POI 的 {@code HeaderStories} 只理解第一节；多节文档统一沿用第一节的页眉页脚。
	 * 提取失败不影响正文转换，仅告警（不输出内容）。
	 */
	private static HeaderFooterStory extractHeaderFooterStory(HWPFDocument hwpfDocument) {
		List<List<Object>> emptyList = java.util.Collections.emptyList();
		try {
			org.apache.poi.hwpf.usermodel.HeaderStories stories =
					new org.apache.poi.hwpf.usermodel.HeaderStories(hwpfDocument);
			List<List<Object>> oddHeader = parseStoryParagraphs(stories.getOddHeader());
			List<List<Object>> oddFooter = parseStoryParagraphs(stories.getOddFooter());
			org.apache.poi.hwpf.usermodel.Section firstSection =
					hwpfDocument.getRange().getSection(0);
			// twips → pt；边距异常小（或为 0）时用 24pt 兜底，保证静态区有可渲染高度。
			double marginTopPoints = Math.max(24.0, firstSection.getMarginTop() / 20.0);
			double marginBottomPoints = Math.max(24.0, firstSection.getMarginBottom() / 20.0);
			return new HeaderFooterStory(
					parseVariantWithFallback(stories.getFirstHeader(), oddHeader), oddHeader,
					parseVariantWithFallback(stories.getEvenHeader(), oddHeader),
					parseVariantWithFallback(stories.getFirstFooter(), oddFooter), oddFooter,
					parseVariantWithFallback(stories.getEvenFooter(), oddFooter),
					marginTopPoints, marginBottomPoints);
		}
		catch (Exception exception) {
			log.warn("DOC 页眉页脚提取失败，转换产物将不含页眉页脚文字", exception);
			return new HeaderFooterStory(emptyList, emptyList, emptyList, emptyList, emptyList,
					emptyList, 24.0, 24.0);
		}
	}

	private static List<List<Object>> parseVariantWithFallback(String variantText,
			List<List<Object>> fallback) {
		List<List<Object>> parsed = parseStoryParagraphs(variantText);
		return parsed.isEmpty() ? fallback : parsed;
	}

	/**
	 * 把页眉/页脚原始文字解析成段落序列。Word 域以控制符 0x13（域开始）/0x14（代码与
	 * 结果的分隔）/0x15（域结束）内嵌在文字里：PAGE 域转为页码占位（缓存的旧页码对
	 * 逐页重复的页脚是错的），其余域保留缓存结果、丢弃域代码；0x0D 分段，
	 * 其余控制字符丢弃。
	 */
	static List<List<Object>> parseStoryParagraphs(String storyText) {
		List<List<Object>> paragraphs = new ArrayList<List<Object>>();
		if (storyText == null) {
			return paragraphs;
		}
		List<Object> currentParagraph = new ArrayList<Object>();
		StringBuilder textBuilder = new StringBuilder();
		StringBuilder fieldCodeBuilder = new StringBuilder();
		StringBuilder fieldResultBuilder = new StringBuilder();
		int fieldDepth = 0;
		boolean inFieldResult = false;
		for (int index = 0; index < storyText.length(); index++) {
			char current = storyText.charAt(index);
			if (current == 0x13) {
				fieldDepth++;
				if (fieldDepth == 1) {
					fieldCodeBuilder.setLength(0);
					fieldResultBuilder.setLength(0);
					inFieldResult = false;
				}
				continue;
			}
			if (current == 0x14) {
				if (fieldDepth == 1) {
					inFieldResult = true;
				}
				continue;
			}
			if (current == 0x15) {
				fieldDepth--;
				if (fieldDepth == 0) {
					flushText(currentParagraph, textBuilder);
					// 只有首个指令词恰为 PAGE 才是页码域（PAGEREF 等同前缀域不是）。
					String[] fieldTokens = fieldCodeBuilder.toString().trim()
							.toUpperCase(java.util.Locale.ROOT).split("\\s+");
					if (fieldTokens.length > 0 && "PAGE".equals(fieldTokens[0])) {
						currentParagraph.add(PAGE_NUMBER_PLACEHOLDER);
					}
					else {
						textBuilder.append(fieldResultBuilder);
					}
				}
				continue;
			}
			if (fieldDepth > 0) {
				if (current >= 0x20) {
					(inFieldResult ? fieldResultBuilder : fieldCodeBuilder).append(current);
				}
				continue;
			}
			if (current == 0x0D) {
				flushText(currentParagraph, textBuilder);
				if (!currentParagraph.isEmpty()) {
					paragraphs.add(currentParagraph);
					currentParagraph = new ArrayList<Object>();
				}
				continue;
			}
			if (current >= 0x20) {
				textBuilder.append(current);
			}
		}
		flushText(currentParagraph, textBuilder);
		if (!currentParagraph.isEmpty()) {
			paragraphs.add(currentParagraph);
		}
		return paragraphs;
	}

	private static void flushText(List<Object> paragraph, StringBuilder textBuilder) {
		if (textBuilder.length() > 0 && textBuilder.toString().trim().length() > 0) {
			paragraph.add(textBuilder.toString());
		}
		textBuilder.setLength(0);
	}

	/**
	 * 把页眉/页脚并入 FO 树：{@code WordToFoConverter} 只排正文，页眉页脚不处理会被
	 * 静默丢掉——那是报告信息（医院名、报告标题、姓名等），不是装饰。
	 *
	 * <p>首页/奇数页/偶数页三档内容可能不同（Word 的「首页不同」「奇偶页不同」），
	 * 因此不能给 simple-page-master 挂单一 region：为每个被引用的页面主控克隆出三个
	 * 变体（各自带独名 region，高度取自 Word 页边距——页眉页脚本就落在上下边距区内，
	 * 不挤占正文、不改分页），用 {@code fo:page-sequence-master} 的条件引用按页型选择
	 * （first 条件在前，先于 odd 命中第 1 页），再注入与各 region 同名的
	 * {@code fo:static-content}；某页型对应的 flow-name 在当页主控上无 region 时，
	 * FOP 自动不渲染该档内容。</p>
	 */
	static void appendHeaderFooterContent(org.w3c.dom.Document foDocument,
			HeaderFooterStory story) {
		if (story == null || story.isEmpty()) {
			return;
		}
		java.util.Map<String, Element> masterByName = new java.util.LinkedHashMap<String, Element>();
		NodeList pageMasters = foDocument.getElementsByTagNameNS(FO_NAMESPACE, "simple-page-master");
		for (int index = 0; index < pageMasters.getLength(); index++) {
			Element pageMaster = (Element) pageMasters.item(index);
			masterByName.put(pageMaster.getAttribute("master-name"), pageMaster);
		}
		java.util.Map<String, String> sequenceMasterByBase =
				new java.util.HashMap<String, String>();
		NodeList pageSequences = foDocument.getElementsByTagNameNS(FO_NAMESPACE, "page-sequence");
		for (int index = 0; index < pageSequences.getLength(); index++) {
			Element pageSequence = (Element) pageSequences.item(index);
			String baseMasterName = pageSequence.getAttribute("master-reference");
			String sequenceMasterName = sequenceMasterByBase.get(baseMasterName);
			if (sequenceMasterName == null) {
				Element baseMaster = masterByName.get(baseMasterName);
				if (baseMaster == null) {
					continue;
				}
				sequenceMasterName = buildVariantMasters(foDocument, baseMaster, story);
				sequenceMasterByBase.put(baseMasterName, sequenceMasterName);
			}
			pageSequence.setAttribute("master-reference", sequenceMasterName);
			Node flowNode = null;
			NodeList children = pageSequence.getChildNodes();
			for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
				Node child = children.item(childIndex);
				if (child instanceof Element && "flow".equals(child.getLocalName())) {
					flowNode = child;
					break;
				}
			}
			for (String variant : PAGE_VARIANTS) {
				if (!story.headerFor(variant).isEmpty()) {
					pageSequence.insertBefore(buildStaticContent(foDocument,
							"hf-before-" + variant, story.headerFor(variant)), flowNode);
				}
				if (!story.footerFor(variant).isEmpty()) {
					pageSequence.insertBefore(buildStaticContent(foDocument,
							"hf-after-" + variant, story.footerFor(variant)), flowNode);
				}
			}
		}
		log.info("DOC 页眉页脚已并入排版，页眉段落数（首/奇/偶）={}/{}/{}，页脚段落数（首/奇/偶）={}/{}/{}",
				story.firstHeader.size(), story.oddHeader.size(), story.evenHeader.size(),
				story.firstFooter.size(), story.oddFooter.size(), story.evenFooter.size());
	}

	/**
	 * 为一个页面主控克隆出首/奇/偶三个变体并组装条件选择的 page-sequence-master，
	 * 返回其 master-name。变体 region 的 region-name 与对应 static-content 的
	 * flow-name 配对（hf-before-* 与 hf-after-*）。
	 */
	private static String buildVariantMasters(org.w3c.dom.Document foDocument,
			Element baseMaster, HeaderFooterStory story) {
		String baseMasterName = baseMaster.getAttribute("master-name");
		Element layoutMasterSet = (Element) baseMaster.getParentNode();
		Element sequenceMaster = foDocument.createElementNS(FO_NAMESPACE,
				"fo:page-sequence-master");
		String sequenceMasterName = baseMasterName + "-hf-seq";
		sequenceMaster.setAttribute("master-name", sequenceMasterName);
		Element alternatives = foDocument.createElementNS(FO_NAMESPACE,
				"fo:repeatable-page-master-alternatives");
		sequenceMaster.appendChild(alternatives);
		for (String variant : PAGE_VARIANTS) {
			Element variantMaster = (Element) baseMaster.cloneNode(true);
			String variantMasterName = baseMasterName + "-hf-" + variant;
			variantMaster.setAttribute("master-name", variantMasterName);
			if (!story.headerFor(variant).isEmpty()) {
				Element regionBefore = foDocument.createElementNS(FO_NAMESPACE,
						"fo:region-before");
				regionBefore.setAttribute("region-name", "hf-before-" + variant);
				regionBefore.setAttribute("extent", story.marginTopPoints + "pt");
				// 贴着正文顶端对齐，模拟 Word 页眉靠近正文的位置。
				regionBefore.setAttribute("display-align", "after");
				variantMaster.appendChild(regionBefore);
			}
			if (!story.footerFor(variant).isEmpty()) {
				Element regionAfter = foDocument.createElementNS(FO_NAMESPACE, "fo:region-after");
				regionAfter.setAttribute("region-name", "hf-after-" + variant);
				regionAfter.setAttribute("extent", story.marginBottomPoints + "pt");
				regionAfter.setAttribute("display-align", "before");
				variantMaster.appendChild(regionAfter);
			}
			layoutMasterSet.appendChild(variantMaster);
			Element conditionalReference = foDocument.createElementNS(FO_NAMESPACE,
					"fo:conditional-page-master-reference");
			conditionalReference.setAttribute("master-reference", variantMasterName);
			if ("first".equals(variant)) {
				conditionalReference.setAttribute("page-position", "first");
			}
			else {
				conditionalReference.setAttribute("odd-or-even", variant);
			}
			alternatives.appendChild(conditionalReference);
		}
		layoutMasterSet.appendChild(sequenceMaster);
		return sequenceMasterName;
	}

	private static Element buildStaticContent(org.w3c.dom.Document foDocument, String flowName,
			List<List<Object>> paragraphs) {
		Element staticContent = foDocument.createElementNS(FO_NAMESPACE, "fo:static-content");
		staticContent.setAttribute("flow-name", flowName);
		for (List<Object> paragraph : paragraphs) {
			Element block = foDocument.createElementNS(FO_NAMESPACE, "fo:block");
			block.setAttribute("font-family", BUNDLED_FONT_FAMILY);
			block.setAttribute("font-size", "9pt");
			block.setAttribute("color", WHITE_TEXT_REPLACEMENT_COLOR);
			block.setAttribute("text-align", "center");
			for (Object segment : paragraph) {
				if (segment == PAGE_NUMBER_PLACEHOLDER) {
					block.appendChild(foDocument.createElementNS(FO_NAMESPACE, "fo:page-number"));
				}
				else {
					block.appendChild(foDocument.createTextNode((String) segment));
				}
			}
			staticContent.appendChild(block);
		}
		return staticContent;
	}

	/** FO 树后处理统计，供日志与测试断言。 */
	static final class FoSanitizeStats {

		int whiteTextFixes;

		int graphicsRemoved;

		int privateUseCharsReplaced;
	}

	/**
	 * FO 树四合一后处理（单次深度遍历）：
	 * ① 全部 font-family 统一替换为内置字体（未映射字体名会被 FOP 静默退回 Base14，
	 * 中文整段渲染成 #）；② 白色文字改正文深色（防白字白底隐形，见类注释）；
	 * ③ 移除 external-graphic / instream-foreign-object（图片丢弃的纵深防线）；
	 * ④ 私用区字符（U+E000–U+F8FF）替换为「•」——.doc 的列表符号常是 Symbol/Wingdings
	 * 字体的私用区码位（真实样本实证 U+F0B7 圆点），字体统一后必然无字形、FOP 渲染成 #，
	 * 替换为通用圆点比满页 # 更接近原意，也不再制造「字体环境坏了」的假信号。
	 */
	static FoSanitizeStats sanitizeFoTree(Element rootElement) {
		FoSanitizeStats stats = new FoSanitizeStats();
		sanitizeElement(rootElement, stats);
		return stats;
	}

	private static void sanitizeElement(Element element, FoSanitizeStats stats) {
		if (element.hasAttribute("font-family")) {
			element.setAttribute("font-family", BUNDLED_FONT_FAMILY);
		}
		String color = element.getAttribute("color");
		if ("white".equalsIgnoreCase(color) || "#ffffff".equalsIgnoreCase(color)
				|| "#fff".equalsIgnoreCase(color)) {
			element.setAttribute("color", WHITE_TEXT_REPLACEMENT_COLOR);
			stats.whiteTextFixes++;
		}
		NodeList children = element.getChildNodes();
		List<Element> childElements = new ArrayList<Element>(children.getLength());
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child instanceof Element) {
				childElements.add((Element) child);
			}
			else if (child instanceof org.w3c.dom.Text) {
				replacePrivateUseChars((org.w3c.dom.Text) child, stats);
			}
		}
		for (Element childElement : childElements) {
			String localName = childElement.getLocalName();
			if ("external-graphic".equals(localName)
					|| "instream-foreign-object".equals(localName)) {
				element.removeChild(childElement);
				stats.graphicsRemoved++;
				continue;
			}
			sanitizeElement(childElement, stats);
		}
	}

	private static void replacePrivateUseChars(org.w3c.dom.Text textNode, FoSanitizeStats stats) {
		String value = textNode.getData();
		StringBuilder replaced = null;
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (current >= '\uE000' && current <= '\uF8FF') {
				if (replaced == null) {
					replaced = new StringBuilder(value);
				}
				replaced.setCharAt(index, '•');
				stats.privateUseCharsReplaced++;
			}
		}
		if (replaced != null) {
			textNode.setData(replaced.toString());
		}
	}

	private static FopFactory resolveFopFactory() {
		FopFactory resolved = fopFactory;
		if (resolved != null) {
			return resolved;
		}
		synchronized (FOP_FACTORY_LOCK) {
			if (fopFactory != null) {
				return fopFactory;
			}
			try {
				fopFactory = buildFopFactory(extractBundledFont());
				log.info("DOC 转换 FOP 排版环境初始化完成，内置字体={}", BUNDLED_FONT_FAMILY);
				return fopFactory;
			}
			catch (Exception exception) {
				// 字体损坏/缺失是构建或部署问题：SERVER_ERROR，绝不能流向「文件无法读取」。
				throw new HealthReportException(FailCode.SERVER_ERROR, 500, exception);
			}
		}
	}

	/**
	 * 构建并<b>实际验证</b>FOP 排版环境（包级可见供损坏字体回归测试直接调用）：
	 * ① fontbox 完整解析字体文件——FOP 对字体的加载是惰性且非严格的，坏字体默认只记
	 * 日志然后静默退回 Base14，中文整段变 # 的「成功」比失败更糟；② 用最小 FO 文档做一次
	 * 预热渲染，把字体注册、嵌入路径在初始化期走通。任何一步失败都在此抛出，由调用方
	 * 归为 SERVER_ERROR——环境问题不得被后续转换失败误报成用户文件不可读。
	 */
	static FopFactory buildFopFactory(Path fontFile) throws Exception {
		validateFontFile(fontFile);
		String fopConf = "<?xml version=\"1.0\"?>\n"
						+ "<fop version=\"1.0\">\n"
						+ "  <renderers>\n"
						+ "    <renderer mime=\"application/pdf\">\n"
						+ "      <fonts>\n"
						+ "        <font embed-url=\"" + fontFile.toUri() + "\">\n"
						+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY
						+ "\" style=\"normal\" weight=\"normal\"/>\n"
						+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY
						+ "\" style=\"normal\" weight=\"bold\"/>\n"
						+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY
						+ "\" style=\"italic\" weight=\"normal\"/>\n"
						+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY
						+ "\" style=\"italic\" weight=\"bold\"/>\n"
						+ "        </font>\n"
						+ "      </fonts>\n"
						+ "    </renderer>\n"
						+ "  </renderers>\n"
						+ "</fop>\n";
		FopConfParser confParser = new FopConfParser(
				new ByteArrayInputStream(fopConf.getBytes(StandardCharsets.UTF_8)),
				Paths.get(".").toUri());
		FopFactoryBuilder factoryBuilder = confParser.getFopFactoryBuilder();
		// POI 产出的 FO 不保证严格合规（真实样本实证），FO 严格校验必须关闭；
		// 用户配置（字体注册）的错误则必须炸出来，不许静默跳过。
		factoryBuilder.setStrictFOValidation(false);
		factoryBuilder.setStrictUserConfigValidation(true);
		FopFactory builtFactory = factoryBuilder.build();
		warmUpRender(builtFactory);
		return builtFactory;
	}

	/** fontbox 完整解析内置字体：解不开或读不出字族名即视为损坏。 */
	private static void validateFontFile(Path fontFile) throws IOException {
		try (org.apache.fontbox.ttf.TrueTypeFont parsedFont =
				new org.apache.fontbox.ttf.OTFParser().parse(fontFile.toFile())) {
			if (parsedFont.getName() == null || parsedFont.getName().isEmpty()) {
				throw new IOException("内置字体文件缺少字族名，视为损坏");
			}
		}
		catch (IOException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new IOException("内置字体文件无法解析，视为损坏", exception);
		}
	}

	/** 用最小 FO 文档做一次预热渲染，让字体注册与嵌入路径在初始化期实际走通。 */
	private static void warmUpRender(FopFactory factory) throws Exception {
		String warmUpFo = "<fo:root xmlns:fo=\"" + FO_NAMESPACE + "\">"
				+ "<fo:layout-master-set>"
				+ "<fo:simple-page-master master-name=\"warmup\" page-width=\"10cm\" page-height=\"10cm\">"
				+ "<fo:region-body/></fo:simple-page-master></fo:layout-master-set>"
				+ "<fo:page-sequence master-reference=\"warmup\">"
				+ "<fo:flow flow-name=\"xsl-region-body\">"
				+ "<fo:block font-family=\"" + BUNDLED_FONT_FAMILY + "\">字体自检</fo:block>"
				+ "</fo:flow></fo:page-sequence></fo:root>";
		ByteArrayOutputStream warmUpOutput = new ByteArrayOutputStream();
		Fop fop = factory.newFop(MimeConstants.MIME_PDF, factory.newFOUserAgent(), warmUpOutput);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.transform(
				new javax.xml.transform.stream.StreamSource(
						new ByteArrayInputStream(warmUpFo.getBytes(StandardCharsets.UTF_8))),
				new SAXResult(fop.getDefaultHandler()));
		if (warmUpOutput.size() == 0) {
			throw new IOException("FOP 预热渲染产出为空，排版环境不可用");
		}
	}

	/** 把内置字体解压到临时文件（FOP 从 jar 内 URL 读字体不可靠）；失败即 SERVER_ERROR。 */
	private static Path extractBundledFont() throws IOException {
		try (InputStream fontStream = DocToPdfConverter.class
				.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
			if (fontStream == null) {
				throw new IOException("classpath 未找到内置字体资源 " + BUNDLED_FONT_RESOURCE);
			}
			Path fontFile = Files.createTempFile("doc-source-han-sans-cn-", ".otf");
			fontFile.toFile().deleteOnExit();
			Files.copy(fontStream, fontFile, StandardCopyOption.REPLACE_EXISTING);
			return fontFile;
		}
	}

}

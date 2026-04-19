package com.smartnote.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.smartnote.entity.Note;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NoteExportService {

    private static final Logger log = LoggerFactory.getLogger(NoteExportService.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PDF_FONT_FAMILY = "SmartNotePdfFont";
    private static final int WORD_IMAGE_MAX_WIDTH_PX = 520;
    private static final int WORD_IMAGE_MAX_HEIGHT_PX = 720;
    private static final Pattern STYLE_WIDTH_PATTERN = Pattern.compile("width\\s*:\\s*(?:min\\(100%,\\s*)?(\\d+)px", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_HEIGHT_PATTERN = Pattern.compile("height\\s*:\\s*(\\d+)px", Pattern.CASE_INSENSITIVE);
    /**
     * Optional bundled fonts under src/main/resources. If present, they are preferred over system fonts.
     */
    private static final List<String> PDF_FONT_RESOURCE_CANDIDATES = List.of(
            "fonts/NotoSansSC-Regular.ttf",
            "fonts/NotoSans-Regular.ttf"
    );
    /**
     * Common Windows font locations for local development.
     */
    private static final List<String> WINDOWS_PDF_FONT_CANDIDATES = List.of(
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simhei.ttf"
    );
    /**
     * Common Linux font locations for server deployment.
     */
    private static final List<String> LINUX_PDF_FONT_CANDIDATES = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf",
            "/usr/local/share/fonts/NotoSansSC-Regular.ttf",
            "/usr/share/fonts/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"
    );

    @Value("${smartnote.export.pdf-font-path:}")
    private String configuredPdfFontPath;

    @Value("${smartnote.export.pdf-font-resource:}")
    private String configuredPdfFontResource;

    @Value("${file.upload-dir:./uploads}")
    private String configuredUploadDir;

    private volatile Path cachedPdfFontResourcePath;
    private volatile String cachedPdfFontResourceName;

    public byte[] exportMarkdown(Note note) {
        String content = "# " + safeTitle(note) + "\n\n" + defaultString(note.getContent());
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportHtml(Note note) {
        return buildDocumentHtml(note, false).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportPdf(Note note) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.toStream(outputStream);
            registerPdfFont(builder);
            builder.withHtmlContent(buildDocumentHtml(note, true), null);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to export note as PDF", exception);
        }
    }

    public byte[] exportWord(Note note) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writeWordDocument(document, note);
            document.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to export note as Word", exception);
        }
    }

    private void writeWordDocument(XWPFDocument document, Note note) {
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titleParagraph.createRun();
        titleRun.setText(safeTitle(note));
        titleRun.setBold(true);
        titleRun.setFontFamily("Microsoft YaHei");
        titleRun.setFontSize(18);

        XWPFParagraph metaParagraph = document.createParagraph();
        metaParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun metaRun = metaParagraph.createRun();
        metaRun.setFontFamily("Microsoft YaHei");
        metaRun.setFontSize(10);
        metaRun.setColor("6B7280");
        metaRun.setText("Exported at: " + DATE_TIME_FORMATTER.format(LocalDateTime.now()));
        if (note.getUpdatedAt() != null) {
            metaRun.addBreak();
            metaRun.setText("Last updated: " + DATE_TIME_FORMATTER.format(note.getUpdatedAt()));
        }

        document.createParagraph().createRun().addBreak();

        Document htmlDocument = Jsoup.parseBodyFragment(resolveContentHtml(note));
        for (Node node : htmlDocument.body().childNodes()) {
            appendBlock(document, node);
        }
    }

    private void appendBlock(XWPFDocument document, Node node) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text().trim();
            if (!text.isEmpty()) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setSpacingAfter(120);
                appendStyledText(paragraph, text, TextStyle.normal());
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        String tagName = element.tagName().toLowerCase();
        switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> appendHeading(document, element, Integer.parseInt(tagName.substring(1)));
            case "p" -> appendParagraph(document, element);
            case "blockquote" -> appendQuote(document, element);
            case "pre" -> appendCodeBlock(document, element);
            case "ul" -> appendList(document, element, false);
            case "ol" -> appendList(document, element, true);
            case "table" -> appendTableFallback(document, element);
            case "img" -> appendImageParagraph(document, element);
            case "hr" -> {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setBorderBottom(Borders.SINGLE);
                paragraph.createRun().addBreak();
            }
            default -> {
                if (!element.text().isBlank() && element.children().isEmpty()) {
                    XWPFParagraph paragraph = document.createParagraph();
                    paragraph.setSpacingAfter(120);
                    appendStyledText(paragraph, element.text(), TextStyle.normal());
                } else {
                    for (Node child : element.childNodes()) {
                        appendBlock(document, child);
                    }
                }
            }
        }
    }

    private void appendHeading(XWPFDocument document, Element element, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(160);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(switch (level) {
            case 1 -> 18;
            case 2 -> 16;
            case 3 -> 14;
            default -> 12;
        });
        run.setText(element.text());
    }

    private void appendParagraph(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        appendStyledNodes(paragraph, element.childNodes(), TextStyle.normal());
    }

    private void appendQuote(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(420);
        paragraph.setBorderLeft(Borders.SINGLE);
        paragraph.setSpacingAfter(120);
        appendStyledNodes(paragraph, element.childNodes(), TextStyle.normal().withItalic());
    }

    private void appendCodeBlock(XWPFDocument document, Element element) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        paragraph.setIndentationLeft(280);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Consolas");
        run.setFontSize(10);
        run.setColor("334155");
        run.setText(element.text());
    }

    private void appendList(XWPFDocument document, Element listElement, boolean ordered) {
        int index = 1;
        for (Element item : listElement.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(360);
            paragraph.setSpacingAfter(80);
            appendStyledText(paragraph, ordered ? index + ". " : "* ", TextStyle.normal());
            appendStyledNodes(paragraph, item.childNodes(), TextStyle.normal());
            index += 1;
        }
    }

    private void appendTableFallback(XWPFDocument document, Element table) {
        for (Element row : table.select("tr")) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(80);
            String rowText = row.select("th,td").eachText().stream()
                    .reduce((left, right) -> left + " | " + right)
                    .orElse("");
            appendStyledText(paragraph, rowText, TextStyle.normal().withBold());
        }
    }

    private void appendStyledNodes(XWPFParagraph paragraph, List<Node> nodes, TextStyle style) {
        for (Node child : nodes) {
            appendStyledNode(paragraph, child, style);
        }
    }

    private void appendStyledNode(XWPFParagraph paragraph, Node node, TextStyle style) {
        if (node instanceof TextNode textNode) {
            if (!textNode.text().isEmpty()) {
                appendStyledText(paragraph, textNode.text(), style);
            }
            return;
        }

        if (!(node instanceof Element element)) {
            return;
        }

        TextStyle nextStyle = style;
        switch (element.tagName().toLowerCase()) {
            case "strong", "b" -> nextStyle = nextStyle.withBold();
            case "em", "i" -> nextStyle = nextStyle.withItalic();
            case "u" -> nextStyle = nextStyle.withUnderline();
            case "code" -> nextStyle = nextStyle.withCode();
            case "img" -> {
                appendInlineImage(paragraph, element);
                return;
            }
            case "br" -> {
                XWPFRun run = paragraph.createRun();
                run.addBreak(BreakType.TEXT_WRAPPING);
                return;
            }
            default -> {
                // Keep current style.
            }
        }

        if ("a".equalsIgnoreCase(element.tagName())) {
            appendStyledNodes(paragraph, element.childNodes(), nextStyle.withUnderline());
            String href = element.attr("href");
            if (href != null && !href.isBlank()) {
                appendStyledText(paragraph, " (" + href + ")", TextStyle.normal().withItalic());
            }
            return;
        }

        appendStyledNodes(paragraph, element.childNodes(), nextStyle);
    }

    private void appendStyledText(XWPFParagraph paragraph, String text, TextStyle style) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(style.code ? "Consolas" : "Microsoft YaHei");
        run.setFontSize(style.code ? 10 : 11);
        run.setBold(style.bold);
        run.setItalic(style.italic);
        run.setUnderline(style.underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
        run.setColor(style.code ? "334155" : "111827");
        run.setText(text);
    }

    private void appendImageParagraph(XWPFDocument document, Element imageElement) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        appendImage(paragraph, imageElement, false);
    }

    private void appendInlineImage(XWPFParagraph paragraph, Element imageElement) {
        if (hasParagraphContent(paragraph)) {
            paragraph.createRun().addBreak(BreakType.TEXT_WRAPPING);
        }
        appendImage(paragraph, imageElement, true);
    }

    private void appendImage(XWPFParagraph paragraph, Element imageElement, boolean addTrailingBreak) {
        try {
            WordImage wordImage = resolveWordImage(imageElement);
            if (wordImage == null) {
                appendImageFallback(paragraph, imageElement);
                return;
            }

            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(wordImage.bytes())) {
                XWPFRun run = paragraph.createRun();
                run.addPicture(
                        inputStream,
                        wordImage.pictureType(),
                        wordImage.fileName(),
                        Units.pixelToEMU(wordImage.widthPx()),
                        Units.pixelToEMU(wordImage.heightPx())
                );
                if (addTrailingBreak) {
                    run.addBreak(BreakType.TEXT_WRAPPING);
                }
            }
        } catch (IOException | InvalidFormatException exception) {
            log.warn("Failed to embed image '{}' into Word export: {}", imageElement.attr("src"), exception.getMessage());
            appendImageFallback(paragraph, imageElement);
        }
    }

    private WordImage resolveWordImage(Element imageElement) throws IOException {
        String src = imageElement.attr("src");
        if (src == null || src.isBlank()) {
            return null;
        }

        ImageBinarySource imageBinarySource = loadImageBinarySource(src.trim());
        if (imageBinarySource == null || imageBinarySource.bytes().length == 0) {
            return null;
        }

        int pictureType = resolveWordPictureType(imageBinarySource.fileName(), imageBinarySource.contentType());
        if (pictureType < 0) {
            log.info("Skipping unsupported Word image type for source '{}'", src);
            return null;
        }

        BufferedImage bufferedImage;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBinarySource.bytes())) {
            bufferedImage = ImageIO.read(inputStream);
        }

        Integer requestedWidth = extractImageWidthPx(imageElement);
        Integer requestedHeight = extractImageHeightPx(imageElement);
        int naturalWidth = bufferedImage != null ? bufferedImage.getWidth() : 0;
        int naturalHeight = bufferedImage != null ? bufferedImage.getHeight() : 0;
        ImageDimensions dimensions = resolveWordImageDimensions(requestedWidth, requestedHeight, naturalWidth, naturalHeight);

        return new WordImage(
                imageBinarySource.bytes(),
                imageBinarySource.fileName(),
                pictureType,
                dimensions.widthPx(),
                dimensions.heightPx()
        );
    }

    private ImageBinarySource loadImageBinarySource(String src) throws IOException {
        if (src.startsWith("data:")) {
            return loadDataUriImage(src);
        }

        Path localUploadPath = resolveLocalUploadPath(src);
        if (localUploadPath != null && Files.isRegularFile(localUploadPath)) {
            return new ImageBinarySource(
                    Files.readAllBytes(localUploadPath),
                    localUploadPath.getFileName().toString(),
                    Files.probeContentType(localUploadPath)
            );
        }

        try {
            URI uri = URI.create(src);
            String scheme = uri.getScheme();
            if (scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                try (InputStream inputStream = uri.toURL().openStream()) {
                    return new ImageBinarySource(
                            inputStream.readAllBytes(),
                            resolveFileNameFromPath(uri.getPath(), "image"),
                            null
                    );
                }
            }
        } catch (IllegalArgumentException exception) {
            log.warn("Ignoring invalid image URI '{}': {}", src, exception.getMessage());
        }

        return null;
    }

    private ImageBinarySource loadDataUriImage(String src) {
        int commaIndex = src.indexOf(',');
        if (commaIndex <= 0) {
            return null;
        }

        String meta = src.substring(5, commaIndex);
        String payload = src.substring(commaIndex + 1);
        String contentType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        String lowerMeta = meta.toLowerCase(Locale.ROOT);
        byte[] bytes;

        if (lowerMeta.contains(";base64")) {
            bytes = Base64.getDecoder().decode(payload);
        } else {
            bytes = URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        }

        return new ImageBinarySource(bytes, "embedded-image" + extensionForContentType(contentType), contentType);
    }

    private Path resolveLocalUploadPath(String src) {
        String uploadRelativePath = extractUploadRelativePath(src);
        if (uploadRelativePath == null || uploadRelativePath.isBlank()) {
            return null;
        }

        String decodedPath = URLDecoder.decode(uploadRelativePath, StandardCharsets.UTF_8);
        Path uploadDirectory = Path.of(configuredUploadDir).toAbsolutePath().normalize();
        Path candidate = uploadDirectory.resolve(decodedPath).normalize();
        if (!candidate.startsWith(uploadDirectory)) {
            log.warn("Rejected image path outside upload directory: {}", src);
            return null;
        }

        return candidate;
    }

    private String extractUploadRelativePath(String src) {
        String normalized = src.trim();
        if (normalized.startsWith("/uploads/")) {
            return normalized.substring("/uploads/".length());
        }

        if (normalized.startsWith("uploads/")) {
            return normalized.substring("uploads/".length());
        }

        try {
            URI uri = URI.create(normalized);
            String path = uri.getPath();
            if (path != null && path.startsWith("/uploads/")) {
                return path.substring("/uploads/".length());
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }

        return null;
    }

    private ImageDimensions resolveWordImageDimensions(
            Integer requestedWidth,
            Integer requestedHeight,
            int naturalWidth,
            int naturalHeight
    ) {
        int width = requestedWidth != null ? requestedWidth : 0;
        int height = requestedHeight != null ? requestedHeight : 0;

        if (width <= 0 && height <= 0 && naturalWidth > 0 && naturalHeight > 0) {
            width = naturalWidth;
            height = naturalHeight;
        } else if (width > 0 && height <= 0 && naturalWidth > 0 && naturalHeight > 0) {
            height = Math.max(1, (int) Math.round((double) naturalHeight * width / naturalWidth));
        } else if (height > 0 && width <= 0 && naturalWidth > 0 && naturalHeight > 0) {
            width = Math.max(1, (int) Math.round((double) naturalWidth * height / naturalHeight));
        } else if (width <= 0 && height <= 0) {
            width = 480;
            height = 320;
        }

        if (width > WORD_IMAGE_MAX_WIDTH_PX) {
            height = Math.max(1, (int) Math.round((double) height * WORD_IMAGE_MAX_WIDTH_PX / width));
            width = WORD_IMAGE_MAX_WIDTH_PX;
        }

        if (height > WORD_IMAGE_MAX_HEIGHT_PX) {
            width = Math.max(1, (int) Math.round((double) width * WORD_IMAGE_MAX_HEIGHT_PX / height));
            height = WORD_IMAGE_MAX_HEIGHT_PX;
        }

        if (width <= 0) {
            width = Math.min(naturalWidth > 0 ? naturalWidth : 480, WORD_IMAGE_MAX_WIDTH_PX);
        }
        if (height <= 0) {
            height = naturalHeight > 0 ? naturalHeight : 320;
        }

        return new ImageDimensions(width, height);
    }

    private Integer extractImageWidthPx(Element imageElement) {
        return extractImageDimension(imageElement, "data-display-width", "width", STYLE_WIDTH_PATTERN);
    }

    private Integer extractImageHeightPx(Element imageElement) {
        return extractImageDimension(imageElement, "data-display-height", "height", STYLE_HEIGHT_PATTERN);
    }

    private Integer extractImageDimension(Element imageElement, String dataAttribute, String htmlAttribute, Pattern stylePattern) {
        String directValue = imageElement.attr(dataAttribute);
        Integer parsedDirectValue = parsePositiveInt(directValue);
        if (parsedDirectValue != null) {
            return parsedDirectValue;
        }

        String htmlValue = imageElement.attr(htmlAttribute);
        Integer parsedHtmlValue = parsePositiveInt(htmlValue);
        if (parsedHtmlValue != null) {
            return parsedHtmlValue;
        }

        String styleValue = imageElement.attr("style");
        if (styleValue == null || styleValue.isBlank()) {
            return null;
        }

        Matcher matcher = stylePattern.matcher(styleValue);
        if (!matcher.find()) {
            return null;
        }

        return parsePositiveInt(matcher.group(1));
    }

    private Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int resolveWordPictureType(String fileName, String contentType) {
        String extension = extensionForFileName(fileName);
        if (extension.isBlank() && contentType != null) {
            extension = extensionForContentType(contentType);
        }

        return switch (extension.toLowerCase(Locale.ROOT)) {
            case ".png" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
            case ".jpg", ".jpeg", ".jfif" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
            case ".gif" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF;
            case ".bmp" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP;
            case ".dib" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_DIB;
            case ".tif", ".tiff" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_TIFF;
            case ".emf" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_EMF;
            case ".wmf" -> org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_WMF;
            default -> -1;
        };
    }

    private String extensionForFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        int queryIndex = fileName.indexOf('?');
        String normalized = queryIndex >= 0 ? fileName.substring(0, queryIndex) : fileName;
        int lastDotIndex = normalized.lastIndexOf('.');
        if (lastDotIndex < 0) {
            return "";
        }

        return normalized.substring(lastDotIndex);
    }

    private String extensionForContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }

        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tiff";
            case "image/x-emf" -> ".emf";
            case "image/x-wmf" -> ".wmf";
            default -> "";
        };
    }

    private String resolveFileNameFromPath(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }

        int lastSlashIndex = path.lastIndexOf('/');
        if (lastSlashIndex < 0 || lastSlashIndex == path.length() - 1) {
            return fallback;
        }

        return path.substring(lastSlashIndex + 1);
    }

    private void appendImageFallback(XWPFParagraph paragraph, Element imageElement) {
        String alt = imageElement.attr("alt");
        String src = imageElement.attr("src");
        String text = (alt != null && !alt.isBlank()) ? alt : resolveFileNameFromPath(src, "图片");
        appendStyledText(paragraph, "[图片未导出: " + text + "]", TextStyle.normal().withItalic());
    }

    private boolean hasParagraphContent(XWPFParagraph paragraph) {
        String paragraphText = paragraph.getText();
        return paragraphText != null && !paragraphText.isBlank();
    }

    private void registerPdfFont(PdfRendererBuilder builder) {
        for (Path candidate : resolvePdfFontCandidates()) {
            if (!isSupportedPdfFont(candidate)) {
                continue;
            }

            try {
                builder.useFont(candidate.toFile(), PDF_FONT_FAMILY);
                log.info("Registered PDF font: {}", candidate);
                return;
            } catch (Exception exception) {
                log.warn("Skipping PDF font {} because registration failed: {}", candidate, exception.getMessage());
            }
        }

        log.info("No custom PDF font registered. PDF export will use renderer fallback fonts.");
    }

    private List<Path> resolvePdfFontCandidates() {
        List<Path> candidates = new ArrayList<>();
        if (configuredPdfFontPath != null && !configuredPdfFontPath.isBlank()) {
            try {
                candidates.add(Path.of(configuredPdfFontPath.trim()));
            } catch (Exception exception) {
                log.warn("Ignoring invalid configured PDF font path '{}': {}", configuredPdfFontPath, exception.getMessage());
            }
        }

        if (configuredPdfFontResource != null && !configuredPdfFontResource.isBlank()) {
            Path configuredResourcePath = materializePdfFontResource(configuredPdfFontResource.trim());
            if (configuredResourcePath != null) {
                candidates.add(configuredResourcePath);
            }
        }

        for (String resourceCandidate : PDF_FONT_RESOURCE_CANDIDATES) {
            Path resourcePath = materializePdfFontResource(resourceCandidate);
            if (resourcePath != null) {
                candidates.add(resourcePath);
            }
        }

        for (String systemPath : resolveSystemPdfFontCandidates()) {
            candidates.add(Path.of(systemPath));
        }

        return candidates;
    }

    private List<String> resolveSystemPdfFontCandidates() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return WINDOWS_PDF_FONT_CANDIDATES;
        }

        return LINUX_PDF_FONT_CANDIDATES;
    }

    private Path materializePdfFontResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        if (resourcePath.equals(cachedPdfFontResourceName) && cachedPdfFontResourcePath != null && Files.isRegularFile(cachedPdfFontResourcePath)) {
            return cachedPdfFontResourcePath;
        }

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return null;
            }

            String fileName = Path.of(resourcePath).getFileName().toString();
            String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".ttf";
            Path tempFile = Files.createTempFile("smartnote-pdf-font-", suffix);
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();

            cachedPdfFontResourceName = resourcePath;
            cachedPdfFontResourcePath = tempFile;
            return tempFile;
        } catch (Exception exception) {
            log.warn("Failed to load bundled PDF font resource '{}': {}", resourcePath, exception.getMessage());
            return null;
        }
    }

    private boolean isSupportedPdfFont(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }

        String fileName = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".ttf") && !fileName.endsWith(".ttc") && !fileName.endsWith(".otf")) {
            log.warn("Skipping unsupported PDF font file (expected .ttf/.ttc/.otf): {}", candidate);
            return false;
        }

        return true;
    }

    private String buildDocumentHtml(Note note, boolean pdfMode) {
        String pageStyle = pdfMode ? "@page { size: A4; margin: 24mm 18mm 20mm 18mm; }" : "";
        String bodyPadding = pdfMode ? "0" : "28px";
        String containerWidth = pdfMode ? "100%" : "960px";
        String containerShadow = pdfMode ? "none" : "0 20px 50px rgba(15, 23, 42, 0.08)";
        String containerBorder = pdfMode ? "none" : "1px solid rgba(15, 23, 42, 0.08)";
        String template = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>%s</title>
                    <style>
                        %s
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            padding: %s;
                            background: #f6f5f4;
                            font-family: '%s', 'Microsoft YaHei', 'SimHei', 'Noto Sans SC', 'PingFang SC', sans-serif;
                            color: #111827;
                            font-size: 12px;
                            line-height: 1.75;
                        }
                        .doc-shell {
                            max-width: %s;
                            margin: 0 auto;
                            background: #ffffff;
                            border: %s;
                            border-radius: 20px;
                            box-shadow: %s;
                            padding: 40px 44px 48px;
                        }
                        h1, h2, h3, h4, h5, h6 { color: #0f172a; margin: 18px 0 10px; }
                        p, li, blockquote, pre { margin: 0 0 10px; }
                        blockquote { border-left: 4px solid #cbd5e1; padding: 8px 0 8px 12px; color: #475569; background: #f8fafc; }
                        pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 10px 12px; white-space: pre-wrap; }
                        code { font-family: 'Consolas', 'Courier New', monospace; }
                        img { max-width: 100%%; }
                        hr { border: none; border-top: 1px solid #e5e7eb; margin: 18px 0; }
                        table { width: 100%%; border-collapse: collapse; margin-bottom: 12px; }
                        th, td { border: 1px solid #e5e7eb; padding: 8px 10px; text-align: left; }
                        th { background: #f8fafc; }
                        .doc-title { text-align: center; font-size: 26px; font-weight: 700; margin-bottom: 8px; }
                        .doc-meta { text-align: center; color: #64748b; font-size: 11px; margin-bottom: 22px; }
                    </style>
                </head>
                <body>
                    <main class="doc-shell">
                        <div class="doc-title">%s</div>
                        <div class="doc-meta">Last updated: %s</div>
                        %s
                    </main>
                </body>
                </html>
                """;
        String updatedAt = note.getUpdatedAt() == null ? "" : DATE_TIME_FORMATTER.format(note.getUpdatedAt());
        String safeTitle = escapeHtml(safeTitle(note));
        return template.formatted(
                safeTitle,
                pageStyle,
                bodyPadding,
                PDF_FONT_FAMILY,
                containerWidth,
                containerBorder,
                containerShadow,
                safeTitle,
                escapeHtml(updatedAt),
                resolveContentHtml(note)
        );
    }

    private String resolveContentHtml(Note note) {
        String html = note.getContentHtml();
        if (html == null || html.isBlank()) {
            html = "<p>" + escapeHtml(defaultString(note.getContent())).replace("\n", "<br/>") + "</p>";
        }

        Document document = Jsoup.parseBodyFragment(html);
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        return document.body().html();
    }

    private String safeTitle(Note note) {
        String title = note.getTitle();
        return title == null || title.isBlank() ? "Untitled Note" : title.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record TextStyle(boolean bold, boolean italic, boolean underline, boolean code) {
        private static TextStyle normal() {
            return new TextStyle(false, false, false, false);
        }

        private TextStyle withBold() {
            return new TextStyle(true, italic, underline, code);
        }

        private TextStyle withItalic() {
            return new TextStyle(bold, true, underline, code);
        }

        private TextStyle withUnderline() {
            return new TextStyle(bold, italic, true, code);
        }

        private TextStyle withCode() {
            return new TextStyle(bold, italic, underline, true);
        }
    }

    private record ImageBinarySource(byte[] bytes, String fileName, String contentType) {
    }

    private record ImageDimensions(int widthPx, int heightPx) {
    }

    private record WordImage(byte[] bytes, String fileName, int pictureType, int widthPx, int heightPx) {
    }
}

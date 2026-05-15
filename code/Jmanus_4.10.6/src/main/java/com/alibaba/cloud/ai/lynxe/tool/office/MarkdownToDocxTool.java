/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.lynxe.tool.office;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.TextFileService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Markdown to DOCX converter tool that transforms markdown files into Word document
 * format. This tool provides access to files in the root plan directory and converts
 * markdown content to DOCX format with proper formatting including headers, lists,
 * tables, code blocks, and more.
 *
 * Keywords: markdown to docx, markdown to word, convert markdown, docx conversion, word
 * document conversion, root directory files, root plan folder.
 *
 * Use this tool to convert markdown files to Word document format in the root plan
 * directory.
 */
public class MarkdownToDocxTool extends AbstractBaseTool<MarkdownToDocxTool.MarkdownToDocxInput> {

	private static final Logger log = LoggerFactory.getLogger(MarkdownToDocxTool.class);

	private static final String TOOL_NAME = "markdown-to-docx";

	/**
	 * Pattern to match markdown image syntax: ![alt text](image_path)
	 */
	private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)");

	private final TextFileService textFileService;

	private final UnifiedDirectoryManager directoryManager;

	private final ToolI18nService toolI18nService;

	/**
	 * Input class for markdown to docx conversion operations
	 */
	public static class MarkdownToDocxInput {

		@JsonProperty("file_path")
		private String filePath;

		@JsonProperty("path")
		private String path;

		@JsonProperty("output_path")
		private String outputPath;

		// Getters and setters
		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getPath() {
			return path != null ? path : filePath;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getOutputPath() {
			return outputPath;
		}

		public void setOutputPath(String outputPath) {
			this.outputPath = outputPath;
		}

	}

	public MarkdownToDocxTool(TextFileService textFileService, UnifiedDirectoryManager directoryManager,
			ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.directoryManager = directoryManager;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(MarkdownToDocxInput input) {
		log.info("MarkdownToDocxTool input: path={}, outputPath={}", input.getPath(), input.getOutputPath());
		try {
			String targetPath = input.getPath();
			String outputPath = input.getOutputPath();

			// Basic parameter validation
			if (targetPath == null || targetPath.trim().isEmpty()) {
				return new ToolExecuteResult("Error: path or file_path parameter is required");
			}

			// Validate and get the absolute path within the root plan directory
			Path markdownFile = validateGlobalPath(targetPath);

			// Check if markdown file exists
			if (!Files.exists(markdownFile)) {
				return new ToolExecuteResult("Error: Markdown file does not exist: " + targetPath);
			}

			// Check if file is markdown
			String fileName = markdownFile.getFileName().toString().toLowerCase();
			if (!fileName.endsWith(".md") && !fileName.endsWith(".markdown")) {
				return new ToolExecuteResult(
						"Error: File is not a markdown file. Expected .md or .markdown extension.");
			}

			// Generate output path if not provided
			if (outputPath == null || outputPath.trim().isEmpty()) {
				String originalName = markdownFile.getFileName().toString();
				int lastDotIndex = originalName.lastIndexOf('.');
				if (lastDotIndex > 0) {
					outputPath = originalName.substring(0, lastDotIndex) + ".docx";
				}
				else {
					outputPath = originalName + ".docx";
				}
			}
			else if (!outputPath.toLowerCase().endsWith(".docx")) {
				outputPath = outputPath + ".docx";
			}

			// Read markdown content
			String markdownContent = Files.readString(markdownFile);

			// Convert markdown to docx
			Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
			Path outputFile = rootPlanDirectory.resolve(outputPath).normalize();

			// Ensure output path is within root plan directory
			if (!outputFile.startsWith(rootPlanDirectory)) {
				return new ToolExecuteResult("Error: Output path is outside root plan directory");
			}

			// Create parent directories if needed
			Files.createDirectories(outputFile.getParent());

			// Convert and save
			convertMarkdownToDocx(markdownContent, outputFile);

			String result = String.format("Successfully converted markdown file to DOCX format.\n\n"
					+ "**Input File**: %s\n" + "**Output File**: %s\n", targetPath, outputPath);

			log.info("Markdown to DOCX conversion completed: {} -> {}", targetPath, outputPath);
			return new ToolExecuteResult(result);

		}
		catch (Exception e) {
			log.error("MarkdownToDocxTool execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Validate and get the absolute path within the root plan directory
	 */
	private Path validateGlobalPath(String filePath) throws IOException {
		if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
			throw new IOException("Error: rootPlanId is required for global file operations but is null or empty");
		}

		// Normalize the file path to remove plan ID prefixes
		String normalizedPath = normalizeFilePath(filePath);

		// Get root plan directory
		Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);

		// Use the centralized method from UnifiedDirectoryManager
		Path rootPlanPath = directoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);

		// If currentPlanId exists and differs from rootPlanId, check subplan directory
		if (this.currentPlanId != null && !this.currentPlanId.isEmpty()
				&& !this.currentPlanId.equals(this.rootPlanId)) {
			Path subplanDirectory = rootPlanDirectory.resolve(this.currentPlanId);
			Path subplanPath = subplanDirectory.resolve(normalizedPath).normalize();

			// Ensure subplan path stays within subplan directory
			if (!subplanPath.startsWith(subplanDirectory)) {
				throw new IOException("Access denied: Invalid file path");
			}

			// If file exists in subplan directory, use it
			if (Files.exists(subplanPath)) {
				return subplanPath;
			}
		}

		return rootPlanPath;
	}

	/**
	 * Normalize file path by removing plan ID prefixes and relative path indicators
	 */
	private String normalizeFilePath(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return filePath;
		}

		// Remove leading slashes and relative path indicators
		String normalized = filePath.trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}

		// Remove "./" prefix if present
		if (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}

		// Remove plan ID prefix (e.g., "plan-1763035234741/")
		if (normalized.matches("^plan-[^/]+/.*")) {
			normalized = normalized.replaceFirst("^plan-[^/]+/", "");
		}

		return normalized;
	}

	/**
	 * Convert markdown content to DOCX format
	 */
	private void convertMarkdownToDocx(String markdownContent, Path outputFile) throws IOException {
		try (XWPFDocument document = new XWPFDocument();
				FileOutputStream out = new FileOutputStream(outputFile.toFile())) {

			String[] lines = markdownContent.split("\n");
			boolean inCodeBlock = false;
			String codeBlockLanguage = "";
			List<String> codeBlockLines = new ArrayList<>();

			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				String trimmedLine = line.trim();

				// Handle code blocks
				if (trimmedLine.startsWith("```")) {
					if (inCodeBlock) {
						// End of code block
						addCodeBlock(document, codeBlockLanguage, codeBlockLines);
						codeBlockLines.clear();
						inCodeBlock = false;
						codeBlockLanguage = "";
					}
					else {
						// Start of code block
						inCodeBlock = true;
						codeBlockLanguage = trimmedLine.substring(3).trim();
					}
					continue;
				}

				if (inCodeBlock) {
					codeBlockLines.add(line);
					continue;
				}

				// Handle headers
				if (trimmedLine.startsWith("#")) {
					int headerLevel = getHeaderLevel(trimmedLine);
					String headerText = trimmedLine.substring(headerLevel).trim();
					addHeader(document, headerLevel, headerText);
					continue;
				}

				// Handle horizontal rules
				if (trimmedLine.matches("^[-*_]{3,}$")) {
					addHorizontalRule(document);
					continue;
				}

				// Handle tables
				if (trimmedLine.startsWith("|") && trimmedLine.endsWith("|")) {
					List<String> tableRows = new ArrayList<>();
					tableRows.add(line);
					// Collect all table rows
					int j = i + 1;
					while (j < lines.length && lines[j].trim().startsWith("|") && lines[j].trim().endsWith("|")) {
						tableRows.add(lines[j]);
						j++;
					}
					addTable(document, tableRows);
					i = j - 1; // Skip processed lines
					continue;
				}

				// Handle lists
				if (isListItem(trimmedLine)) {
					List<String> listItems = new ArrayList<>();
					listItems.add(line);
					// Collect all list items
					int j = i + 1;
					while (j < lines.length && (isListItem(lines[j].trim()) || lines[j].trim().isEmpty())) {
						if (!lines[j].trim().isEmpty()) {
							listItems.add(lines[j]);
						}
						j++;
					}
					addList(document, listItems);
					i = j - 1; // Skip processed lines
					continue;
				}

				// Handle regular paragraphs
				if (!trimmedLine.isEmpty()) {
					addParagraph(document, line);
				}
				else {
					// Empty line - add spacing
					addEmptyParagraph(document);
				}
			}

			// Handle any remaining code block
			if (inCodeBlock && !codeBlockLines.isEmpty()) {
				addCodeBlock(document, codeBlockLanguage, codeBlockLines);
			}

			document.write(out);
			log.info("DOCX file created successfully: {}", outputFile);
		}
	}

	/**
	 * Get header level from markdown header line
	 */
	private int getHeaderLevel(String line) {
		int level = 0;
		while (level < line.length() && line.charAt(level) == '#') {
			level++;
		}
		return Math.min(level, 6); // Limit to 6 levels
	}

	/**
	 * Add header to document
	 */
	private void addHeader(XWPFDocument document, int level, String text) {
		XWPFParagraph paragraph = document.createParagraph();
		XWPFRun run = paragraph.createRun();
		run.setText(text);
		run.setBold(true);

		// Set font size based on header level
		int fontSize = 24 - (level * 2);
		if (fontSize < 10) {
			fontSize = 10;
		}
		run.setFontSize(fontSize);

		// Set alignment
		paragraph.setAlignment(ParagraphAlignment.LEFT);
	}

	/**
	 * Add paragraph to document with markdown formatting and image support
	 */
	private void addParagraph(XWPFDocument document, String text) {
		XWPFParagraph paragraph = document.createParagraph();

		// Check if line contains only an image (standalone image)
		Matcher imageMatcher = IMAGE_PATTERN.matcher(text.trim());
		if (imageMatcher.matches() && text.trim().equals(imageMatcher.group(0))) {
			// Standalone image - add it directly
			String imagePath = imageMatcher.group(2);
			String altText = imageMatcher.group(1);
			addImage(document, paragraph, imagePath, altText);
			return;
		}

		// Process text with inline images
		Matcher matcher = IMAGE_PATTERN.matcher(text);
		int lastEnd = 0;
		boolean hasImage = false;

		while (matcher.find()) {
			// Add text before image
			if (matcher.start() > lastEnd) {
				String textBefore = text.substring(lastEnd, matcher.start());
				textBefore = processInlineFormatting(textBefore);
				if (!textBefore.trim().isEmpty()) {
					XWPFRun run = paragraph.createRun();
					run.setText(textBefore);
				}
			}

			// Add image
			String imagePath = matcher.group(2);
			String altText = matcher.group(1);
			addImage(document, paragraph, imagePath, altText);
			hasImage = true;
			lastEnd = matcher.end();
		}

		// Add remaining text after last image
		if (lastEnd < text.length()) {
			String textAfter = text.substring(lastEnd);
			textAfter = processInlineFormatting(textAfter);
			if (!textAfter.trim().isEmpty()) {
				XWPFRun run = paragraph.createRun();
				run.setText(textAfter);
			}
		}

		// If no images found, add text normally
		if (!hasImage) {
			text = processInlineFormatting(text);
			if (!text.trim().isEmpty()) {
				XWPFRun run = paragraph.createRun();
				run.setText(text);
			}
		}
	}

	/**
	 * Add empty paragraph for spacing
	 */
	private void addEmptyParagraph(XWPFDocument document) {
		document.createParagraph();
	}

	/**
	 * Add horizontal rule
	 */
	private void addHorizontalRule(XWPFDocument document) {
		XWPFParagraph paragraph = document.createParagraph();
		XWPFRun run = paragraph.createRun();
		run.setText("────────────────────────────────────────");
		run.setColor("CCCCCC");
	}

	/**
	 * Process inline markdown formatting (bold, italic, code) Note: Images are handled
	 * separately, so we don't remove image syntax here
	 */
	private String processInlineFormatting(String text) {
		// Remove markdown formatting (but keep images for separate processing)
		text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1"); // Bold
		text = text.replaceAll("\\*(.+?)\\*", "$1"); // Italic
		text = text.replaceAll("`(.+?)`", "$1"); // Inline code
		text = text.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1"); // Links (but not
																		// images)
		return text;
	}

	/**
	 * Add image to document paragraph
	 */
	private void addImage(XWPFDocument document, XWPFParagraph paragraph, String imagePath, String altText) {
		try {
			// Resolve image path relative to root plan directory
			Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
			Path imageFile = resolveImagePath(rootPlanDirectory, imagePath);

			if (imageFile == null || !Files.exists(imageFile)) {
				log.warn("Image file not found: {}", imagePath);
				// Add placeholder text
				XWPFRun run = paragraph.createRun();
				run.setText("[Image not found: " + imagePath + "]");
				run.setColor("FF0000");
				return;
			}

			// Determine image type
			String fileName = imageFile.getFileName().toString().toLowerCase();
			int pictureType = getPictureType(fileName);

			// Add image to paragraph
			XWPFRun run = paragraph.createRun();
			try (FileInputStream fis = new FileInputStream(imageFile.toFile())) {
				// Add picture with default size (can be adjusted)
				// Width: 400 pixels, Height: auto (maintain aspect ratio)
				run.addPicture(fis, pictureType, imageFile.getFileName().toString(), Units.toEMU(400),
						Units.toEMU(300)); // 400x300 EMU (approximately 400x300 pixels)
			}

			// Add alt text as caption if provided
			if (altText != null && !altText.trim().isEmpty()) {
				XWPFParagraph captionParagraph = document.createParagraph();
				XWPFRun captionRun = captionParagraph.createRun();
				captionRun.setText(altText);
				captionRun.setItalic(true);
				captionRun.setFontSize(9);
				captionParagraph.setAlignment(ParagraphAlignment.CENTER);
			}

			log.debug("Added image to document: {}", imagePath);
		}
		catch (Exception e) {
			log.error("Error adding image to document: {}", imagePath, e);
			// Add error message
			XWPFRun run = paragraph.createRun();
			run.setText("[Error loading image: " + imagePath + "]");
			run.setColor("FF0000");
		}
	}

	/**
	 * Resolve image path relative to root plan directory Handles both file system paths
	 * and API URLs like /api/file-browser/download/{planId}?path={relativePath}
	 */
	private Path resolveImagePath(Path rootPlanDirectory, String imagePath) {
		try {
			// Check if the path is an API URL
			if (imagePath != null && imagePath.startsWith("/api/file-browser/download/")) {
				// Extract the path parameter from the API URL
				// Format: /api/file-browser/download/{planId}?path={relativePath}
				String actualPath = extractPathFromApiUrl(imagePath);
				if (actualPath != null) {
					imagePath = actualPath;
					log.debug("Extracted file path from API URL: {}", actualPath);
				}
				else {
					log.warn("Failed to extract path from API URL: {}", imagePath);
					return null;
				}
			}

			// Normalize the path
			String normalizedPath = normalizeFilePath(imagePath);

			// Try root plan directory first
			Path imageFile = rootPlanDirectory.resolve(normalizedPath).normalize();

			// Ensure path is within root plan directory
			if (imageFile.startsWith(rootPlanDirectory) && Files.exists(imageFile)) {
				return imageFile;
			}

			// If currentPlanId exists and differs from rootPlanId, check subplan
			// directory
			if (this.currentPlanId != null && !this.currentPlanId.isEmpty()
					&& !this.currentPlanId.equals(this.rootPlanId)) {
				Path subplanDirectory = rootPlanDirectory.resolve(this.currentPlanId);
				Path subplanImageFile = subplanDirectory.resolve(normalizedPath).normalize();

				if (subplanImageFile.startsWith(subplanDirectory) && Files.exists(subplanImageFile)) {
					return subplanImageFile;
				}
			}

			return null;
		}
		catch (Exception e) {
			log.error("Error resolving image path: {}", imagePath, e);
			return null;
		}
	}

	/**
	 * Extract the actual file path from an API URL Format:
	 * /api/file-browser/download/{planId}?path={relativePath}
	 * @param apiUrl The API URL string
	 * @return The decoded file path, or null if extraction fails
	 */
	private String extractPathFromApiUrl(String apiUrl) {
		try {
			// Find the path parameter in the query string
			int pathParamIndex = apiUrl.indexOf("?path=");
			if (pathParamIndex == -1) {
				log.warn("No path parameter found in API URL: {}", apiUrl);
				return null;
			}

			// Extract the path value (everything after "?path=" until next & or end of
			// string)
			int startIndex = pathParamIndex + 6; // 6 is length of "?path="
			int endIndex = apiUrl.indexOf("&", startIndex);
			String pathValue = (endIndex == -1) ? apiUrl.substring(startIndex) : apiUrl.substring(startIndex, endIndex);

			// URL decode the path
			String decodedPath = URLDecoder.decode(pathValue, StandardCharsets.UTF_8);

			// Normalize path separators (handle both / and \)
			decodedPath = decodedPath.replace("\\", "/");

			return decodedPath;
		}
		catch (Exception e) {
			log.error("Error extracting path from API URL: {}", apiUrl, e);
			return null;
		}
	}

	/**
	 * Get Apache POI picture type from file extension
	 */
	private int getPictureType(String fileName) {
		if (fileName.endsWith(".png")) {
			return Document.PICTURE_TYPE_PNG;
		}
		else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
			return Document.PICTURE_TYPE_JPEG;
		}
		else if (fileName.endsWith(".gif")) {
			return Document.PICTURE_TYPE_GIF;
		}
		else if (fileName.endsWith(".bmp")) {
			return Document.PICTURE_TYPE_BMP;
		}
		else if (fileName.endsWith(".wmf")) {
			return Document.PICTURE_TYPE_WMF;
		}
		else if (fileName.endsWith(".emf")) {
			return Document.PICTURE_TYPE_EMF;
		}
		else {
			// Default to PNG
			return Document.PICTURE_TYPE_PNG;
		}
	}

	/**
	 * Check if line is a list item
	 */
	private boolean isListItem(String line) {
		return line.matches("^[-*+]\\s+.+") || line.matches("^\\d+\\.\\s+.+");
	}

	/**
	 * Add list to document
	 */
	private void addList(XWPFDocument document, List<String> listItems) {
		for (String item : listItems) {
			String trimmed = item.trim();
			if (trimmed.isEmpty()) {
				continue;
			}

			XWPFParagraph paragraph = document.createParagraph();
			XWPFRun run = paragraph.createRun();

			// Remove list markers
			String text = trimmed.replaceFirst("^[-*+]\\s+", "").replaceFirst("^\\d+\\.\\s+", "");
			text = processInlineFormatting(text);

			// Add bullet or number
			if (trimmed.matches("^\\d+\\.\\s+.+")) {
				// Numbered list
				String number = trimmed.substring(0, trimmed.indexOf('.'));
				run.setText(number + ". " + text);
			}
			else {
				// Bullet list
				run.setText("• " + text);
			}

			// Indent
			paragraph.setIndentationLeft(360); // 0.25 inch
		}
	}

	/**
	 * Add table to document
	 */
	private void addTable(XWPFDocument document, List<String> tableRows) {
		if (tableRows.isEmpty()) {
			return;
		}

		// Parse table rows
		List<List<String>> rows = new ArrayList<>();
		boolean isHeaderRow = true;

		for (String row : tableRows) {
			// Skip separator row
			if (row.trim().matches("^\\|[-\\s:]+\\|$")) {
				isHeaderRow = false;
				continue;
			}

			List<String> cells = parseTableRow(row);
			if (!cells.isEmpty()) {
				rows.add(cells);
			}
		}

		if (rows.isEmpty()) {
			return;
		}

		// Determine number of columns
		int numColumns = rows.stream().mapToInt(List::size).max().orElse(1);

		// Create table
		XWPFTable table = document.createTable(rows.size(), numColumns);

		// Fill table
		for (int i = 0; i < rows.size(); i++) {
			XWPFTableRow tableRow = table.getRow(i);
			List<String> rowData = rows.get(i);

			for (int j = 0; j < numColumns; j++) {
				XWPFTableCell cell = tableRow.getCell(j);
				String cellText = j < rowData.size() ? rowData.get(j).trim() : "";

				// Remove markdown formatting
				cellText = processInlineFormatting(cellText);
				cellText = cellText.replace("<br>", "\n");

				XWPFParagraph cellParagraph = cell.getParagraphs().get(0);
				XWPFRun cellRun = cellParagraph.createRun();
				cellRun.setText(cellText);

				// Make header row bold
				if (i == 0 && isHeaderRow) {
					cellRun.setBold(true);
				}
			}
		}
	}

	/**
	 * Parse a markdown table row into cells
	 */
	private List<String> parseTableRow(String row) {
		List<String> cells = new ArrayList<>();
		// Remove leading and trailing |
		String trimmed = row.trim();
		if (trimmed.startsWith("|")) {
			trimmed = trimmed.substring(1);
		}
		if (trimmed.endsWith("|")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}

		// Split by | but handle escaped pipes
		String[] parts = trimmed.split("(?<!\\\\)\\|");
		for (String part : parts) {
			cells.add(part.trim().replace("\\|", "|"));
		}

		return cells;
	}

	/**
	 * Add code block to document
	 */
	private void addCodeBlock(XWPFDocument document, String language, List<String> codeLines) {
		XWPFParagraph paragraph = document.createParagraph();
		XWPFRun run = paragraph.createRun();

		// Join code lines
		String code = String.join("\n", codeLines);
		run.setText(code);

		// Style as code (monospace font)
		run.setFontFamily("Courier New");
		run.setFontSize(10);

		// Set background color (light gray)
		run.getCTR().addNewRPr().addNewShd().setFill("F5F5F5");

		// Add spacing
		paragraph.setSpacingAfter(120);
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		return new ToolStateInfo(null, "");
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("markdown-to-docx-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("markdown-to-docx-tool");
	}

	@Override
	public Class<MarkdownToDocxInput> getInputType() {
		return MarkdownToDocxInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up resources for plan: {}", planId);
		}
	}

	@Override
	public String getServiceGroup() {
		return "import-export";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
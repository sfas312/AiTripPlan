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
package com.alibaba.cloud.ai.lynxe.tool.textOperator.fileOperators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.TextFileService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService;

/**
 * Read file operator that reads file contents from the local filesystem. This operator
 * provides access to files that can be accessed across all sub-plans within the same
 * execution context.
 *
 * Keywords: global files, root directory, root folder, root plan directory, global file
 * read operations, root file access, cross-plan files.
 */
public class ReadFileOperator extends AbstractBaseTool<ReadFileOperator.ReadFileInput> {

	private static final Logger log = LoggerFactory.getLogger(ReadFileOperator.class);

	private static final String TOOL_NAME = "read-file-operator";

	/**
	 * Input class for read file operations
	 */
	public static class ReadFileInput {

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		@com.fasterxml.jackson.annotation.JsonProperty("path")
		private String path;

		@com.fasterxml.jackson.annotation.JsonProperty("offset")
		private Integer offset;

		@com.fasterxml.jackson.annotation.JsonProperty("limit")
		private Integer limit;

		@com.fasterxml.jackson.annotation.JsonProperty("bypass_limit")
		private Boolean bypassLimit;

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

		public Integer getOffset() {
			return offset;
		}

		public void setOffset(Integer offset) {
			this.offset = offset;
		}

		public Integer getLimit() {
			return limit;
		}

		public void setLimit(Integer limit) {
			this.limit = limit;
		}

		public Boolean getBypassLimit() {
			return bypassLimit;
		}

		public void setBypassLimit(Boolean bypassLimit) {
			this.bypassLimit = bypassLimit;
		}

	}

	private final TextFileService textFileService;

	private final ShortUrlService shortUrlService;

	private final ToolI18nService toolI18nService;

	public ReadFileOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ShortUrlService shortUrlService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.shortUrlService = shortUrlService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ReadFileInput input) {
		log.info("ReadFileOperator input: path={}", input.getPath());
		try {
			String targetPath = input.getPath();

			// Basic parameter validation
			if (targetPath == null) {
				return new ToolExecuteResult("Error: path or file_path parameter is required");
			}

			// Replace short URLs in path
			targetPath = replaceShortUrls(targetPath);

			Integer offset = input.getOffset();
			Integer limit = input.getLimit();
			Boolean bypassLimit = input.getBypassLimit();

			return readFile(targetPath, offset, limit, bypassLimit);
		}
		catch (Exception e) {
			log.error("ReadFileOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Replace short URLs in a string with real URLs
	 * @param text The text that may contain short URLs
	 * @return The text with short URLs replaced by real URLs
	 */
	private String replaceShortUrls(String text) {
		if (text == null || text.isEmpty() || this.rootPlanId == null || this.rootPlanId.isEmpty()
				|| this.shortUrlService == null) {
			return text;
		}

		// Check if short URL feature is enabled
		Boolean enableShortUrl = textFileService.getLynxeProperties().getEnableShortUrl();
		if (enableShortUrl == null || !enableShortUrl) {
			return text; // Skip replacement if disabled
		}

		// Pattern to match short URLs: http://s@Url.a/ followed by digits
		Pattern shortUrlPattern = Pattern.compile(Pattern.quote(ShortUrlService.SHORT_URL_PREFIX) + "\\d+");
		Matcher matcher = shortUrlPattern.matcher(text);
		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String shortUrl = matcher.group();
			String realUrl = shortUrlService.getRealUrl(this.rootPlanId, shortUrl);
			if (realUrl != null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(realUrl));
				log.debug("Replaced short URL {} with real URL {}", shortUrl, realUrl);
			}
			else {
				log.warn("Short URL not found in mapping: {}", shortUrl);
				// Keep the short URL if mapping not found
				matcher.appendReplacement(result, Matcher.quoteReplacement(shortUrl));
			}
		}
		matcher.appendTail(result);

		return result.toString();
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
	 * Validate and get the absolute path within the plan/subplan directory
	 */
	private Path validateGlobalPath(String filePath) throws IOException {
		if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
			throw new IOException("Error: rootPlanId is required for global file operations but is null or empty");
		}

		// Normalize the file path to remove plan ID prefixes
		String normalizedPath = normalizeFilePath(filePath);

		// Check file type for non-directory operations
		if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/") && !isSupportedFileType(normalizedPath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Get root plan directory
		Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
		UnifiedDirectoryManager directoryManager = textFileService.getUnifiedDirectoryManager();

		// For ReadFileOperator, check root plan directory first, then subplan directory
		// if applicable
		// This allows accessing files in root plan directory even when in subplan context
		// Use the centralized method from UnifiedDirectoryManager
		Path rootPlanPath = directoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);

		// If file exists in root plan directory, use it
		if (Files.exists(rootPlanPath)) {
			return rootPlanPath;
		}

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

		// If file doesn't exist in either location, return root plan path for reading
		// (will check existence before reading)
		return rootPlanPath;
	}

	/**
	 * Check if file type is supported
	 */
	private boolean isSupportedFileType(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return false;
		}

		String extension = getFileExtension(filePath);
		return UnifiedDirectoryManager.SUPPORTED_TEXT_FILE_EXTENSIONS.contains(extension.toLowerCase());
	}

	/**
	 * Get file extension
	 */
	private String getFileExtension(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return "";
		}

		int lastDotIndex = filePath.lastIndexOf('.');
		if (lastDotIndex == -1 || lastDotIndex == filePath.length() - 1) {
			return "";
		}

		return filePath.substring(lastDotIndex);
	}

	/**
	 * Read file contents with optional offset and limit
	 * @param filePath The file path to read
	 * @param offset Optional line number to start reading from (1-based)
	 * @param limit Optional number of lines to read
	 * @param bypassLimit If true, bypasses the 300-line limit for full file reads
	 * @return ToolExecuteResult with file contents in format: LINE_NUMBER|LINE_CONTENT
	 */
	private ToolExecuteResult readFile(String filePath, Integer offset, Integer limit, Boolean bypassLimit) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Check if file exists
			if (!Files.exists(absolutePath)) {
				return new ToolExecuteResult("Error: File does not exist: " + filePath);
			}

			// Check if path is a directory (Windows throws AccessDeniedException when
			// trying to read a directory)
			if (Files.isDirectory(absolutePath)) {
				return new ToolExecuteResult("Error: Cannot read directory as file. Path is a directory: " + filePath);
			}

			java.util.List<String> lines = Files.readAllLines(absolutePath);

			// Handle empty file
			if (lines.isEmpty()) {
				return new ToolExecuteResult("File is empty.");
			}

			// Protection: If file is too large and no offset/limit provided, suggest
			// using offset/limit
			// Unless bypassLimit flag is set to true
			boolean isFullRead = (offset == null && limit == null);
			boolean shouldBypassLimit = (bypassLimit != null && bypassLimit);
			int maxLinesForFullRead = textFileService.getLynxeProperties().getMaxLinesForFullRead();
			if (isFullRead && !shouldBypassLimit && lines.size() > maxLinesForFullRead) {
				// Calculate character count from lines
				long charCount = lines.stream().mapToLong(String::length).sum() + lines.size();
				return new ToolExecuteResult(String
					.format("File is too large (%d lines, %d characters, exceeds limit of %d lines). "
							+ "Please use one of the following approaches:\n"
							+ "1. Use offset and limit parameters to read specific line ranges (e.g., offset=1, limit=100)\n"
							+ "2. Use search functionality to find relevant sections\n"
							+ "3. Set bypass_limit=true to read the entire file (use with caution for very large files)\n\n"
							+ "Example: Read first 100 lines with offset=1, limit=100", lines.size(), charCount,
							maxLinesForFullRead));
			}

			// Determine read range
			int startIndex = 0;
			int endIndex = lines.size();

			if (offset != null) {
				// Validate offset (1-based, must be >= 1)
				if (offset < 1) {
					return new ToolExecuteResult("Error: offset must be >= 1 (line numbers start from 1)");
				}
				if (offset > lines.size()) {
					return new ToolExecuteResult(
							"Error: offset exceeds file range (file has " + lines.size() + " lines)");
				}
				startIndex = offset - 1; // Convert to 0-based index
			}

			if (limit != null) {
				// Validate limit (must be > 0)
				if (limit < 1) {
					return new ToolExecuteResult("Error: limit must be >= 1");
				}
				endIndex = Math.min(startIndex + limit, lines.size());
			}

			// Build result with format: LINE_NUMBER|LINE_CONTENT
			// Line numbers are right-aligned and padded to 6 characters
			StringBuilder result = new StringBuilder();
			for (int i = startIndex; i < endIndex; i++) {
				int lineNumber = i + 1; // 1-based line number
				String line = lines.get(i);
				// Format: right-aligned 6-character line number, then |, then content
				result.append(String.format("%6d|%s\n", lineNumber, line));
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error reading file: {}", filePath, e);
			return new ToolExecuteResult("Error reading file: " + e.getMessage());
		}
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
		return toolI18nService.getDescription("read-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("read-file-operator");
	}

	@Override
	public Class<ReadFileInput> getInputType() {
		return ReadFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up file resources for plan: {}", planId);
			// Cleanup if needed - the TextFileService handles the main cleanup
		}
	}

	@Override
	public String getServiceGroup() {
		return "fs";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

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
 * Read file operator specifically for external_link directory. This operator reads file
 * contents from the linked_external directory (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file read operations.
 */
public class ReadExternalLinkFileOperator extends AbstractBaseTool<ReadExternalLinkFileOperator.ReadFileInput> {

	private static final Logger log = LoggerFactory.getLogger(ReadExternalLinkFileOperator.class);

	private static final String TOOL_NAME = "read-external-link-file-operator";

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

	private final ToolI18nService toolI18nService;

	private final BaseFileOperator baseOperator;

	public ReadExternalLinkFileOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ShortUrlService shortUrlService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, shortUrlService) {
		};
	}

	@Override
	public ToolExecuteResult run(ReadFileInput input) {
		log.info("ReadExternalLinkFileOperator input: path={}", input.getPath());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String targetPath = input.getPath();

			// Basic parameter validation
			if (targetPath == null) {
				return new ToolExecuteResult("Error: path or file_path parameter is required");
			}

			// Replace short URLs in path
			targetPath = baseOperator.replaceShortUrls(targetPath);

			Integer offset = input.getOffset();
			Integer limit = input.getLimit();
			Boolean bypassLimit = input.getBypassLimit();

			return readFile(targetPath, offset, limit, bypassLimit);
		}
		catch (Exception e) {
			log.error("ReadExternalLinkFileOperator execution failed", e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Tool execution failed: " + errorMessage);
		}
	}

	/**
	 * Validate and get the absolute path within the external_link directory
	 */
	private Path validateExternalLinkPath(String filePath) throws IOException {
		if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
			throw new IOException(
					"Error: rootPlanId is required for external_link file operations but is null or empty");
		}

		// Normalize the file path to remove plan ID prefixes
		String normalizedPath = baseOperator.normalizeFilePath(filePath);

		// Check file type for non-directory operations
		if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/")
				&& !baseOperator.isSupportedFileType(normalizedPath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Use UnifiedDirectoryManager to resolve external_link path
		UnifiedDirectoryManager directoryManager = textFileService.getUnifiedDirectoryManager();
		return directoryManager.resolveAndValidateExternalLinkPath(this.rootPlanId, normalizedPath);
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
			Path absolutePath = validateExternalLinkPath(filePath);

			// Check if file exists
			if (!Files.exists(absolutePath)) {
				return new ToolExecuteResult("Error: File does not exist: " + filePath);
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
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error reading file: " + errorMessage);
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
		return toolI18nService.getDescription("read-external-link-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("read-external-link-file-operator");
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
		return "fs-ext";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

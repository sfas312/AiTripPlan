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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Replace file operator specifically for external_link directory. This operator performs
 * exact string replacement in files in the linked_external directory (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file replace operations, replace text, StrReplace.
 */
public class ReplaceExternalLinkFileOperator
		extends AbstractBaseTool<ReplaceExternalLinkFileOperator.ReplaceFileInput> {

	private static final Logger log = LoggerFactory.getLogger(ReplaceExternalLinkFileOperator.class);

	private static final String TOOL_NAME = "replace-external-link-file-operator";

	/**
	 * Input class for replace file operations
	 */
	public static class ReplaceFileInput {

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		@com.fasterxml.jackson.annotation.JsonProperty("old_string")
		private String oldString;

		@com.fasterxml.jackson.annotation.JsonProperty("new_string")
		private String newString;

		// Getters and setters
		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getOldString() {
			return oldString;
		}

		public void setOldString(String oldString) {
			this.oldString = oldString;
		}

		public String getNewString() {
			return newString;
		}

		public void setNewString(String newString) {
			this.newString = newString;
		}

	}

	private final TextFileService textFileService;

	private final ToolI18nService toolI18nService;

	private final BaseFileOperator baseOperator;

	public ReplaceExternalLinkFileOperator(TextFileService textFileService,
			SmartContentSavingService innerStorageService, ShortUrlService shortUrlService,
			ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, shortUrlService) {
		};
	}

	@Override
	public ToolExecuteResult run(ReplaceFileInput input) {
		log.info("ReplaceExternalLinkFileOperator input: file_path={}", input.getFilePath());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String targetPath = input.getFilePath();
			String oldString = input.getOldString();
			String newString = input.getNewString();

			// Basic parameter validation
			if (targetPath == null) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}
			if (oldString == null || newString == null) {
				return new ToolExecuteResult("Error: old_string and new_string parameters are required");
			}

			// Replace short URLs in path and strings
			targetPath = baseOperator.replaceShortUrls(targetPath);
			oldString = baseOperator.replaceShortUrls(oldString);
			newString = baseOperator.replaceShortUrls(newString);

			return replaceText(targetPath, oldString, newString);
		}
		catch (Exception e) {
			log.error("ReplaceExternalLinkFileOperator execution failed", e);
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
	 * Replace text in file (StrReplace tool implementation) Performs exact string
	 * replacement with uniqueness validation
	 */
	private ToolExecuteResult replaceText(String filePath, String oldString, String newString) {
		try {
			Path absolutePath = validateExternalLinkPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			// Validate that old_string and new_string are different
			if (oldString.equals(newString)) {
				return new ToolExecuteResult(
						"Error: new_string must be different from old_string. No changes would be made.");
			}

			String content = Files.readString(absolutePath);

			// Check if old_string exists in the file content
			if (!content.contains(oldString)) {
				log.warn("old_string not found in file: {}", absolutePath);
				return new ToolExecuteResult("Error: old_string was not found in file: " + filePath);
			}

			// Count occurrences to validate uniqueness
			int occurrenceCount = countOccurrences(content, oldString);
			if (occurrenceCount > 1) {
				return new ToolExecuteResult(String.format("Error: old_string is not unique (found %d occurrences). "
						+ "Please provide a larger string with more surrounding context to make it unique, "
						+ "or use a more specific match.", occurrenceCount));
			}

			// Perform replacement (only first occurrence since we validated uniqueness)
			String newContent = content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
			Files.writeString(absolutePath, newContent);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			log.info("Text replaced in file: {}", absolutePath);
			return new ToolExecuteResult("Replacement successful in file: " + filePath);
		}
		catch (IOException e) {
			log.error("Error replacing text in file: {}", filePath, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error replacing text in file: " + errorMessage);
		}
	}

	/**
	 * Count occurrences of a string in content (exact matching)
	 */
	private int countOccurrences(String content, String searchString) {
		int count = 0;
		int index = 0;
		while ((index = content.indexOf(searchString, index)) != -1) {
			count++;
			index += searchString.length();
		}
		return count;
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
		return toolI18nService.getDescription("replace-external-link-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("replace-external-link-file-operator");
	}

	@Override
	public Class<ReplaceFileInput> getInputType() {
		return ReplaceFileInput.class;
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

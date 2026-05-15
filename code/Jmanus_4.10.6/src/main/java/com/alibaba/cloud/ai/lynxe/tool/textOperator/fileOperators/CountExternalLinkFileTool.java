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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.TextFileService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;

/**
 * Count file tool specifically for external_link directory. This tool counts the total
 * lines and characters in a given file in the linked_external directory (external
 * folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file count operations, count file.
 */
public class CountExternalLinkFileTool extends AbstractBaseTool<CountExternalLinkFileTool.CountFileInput> {

	private static final Logger log = LoggerFactory.getLogger(CountExternalLinkFileTool.class);

	private static final String TOOL_NAME = "count-external-link-file";

	/**
	 * Input class for count file operations
	 */
	public static class CountFileInput {

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		// Getters and setters
		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

	}

	private final TextFileService textFileService;

	private final ToolI18nService toolI18nService;

	private final BaseFileOperator baseOperator;

	public CountExternalLinkFileTool(TextFileService textFileService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, null) {
		};
	}

	@Override
	public ToolExecuteResult run(CountFileInput input) {
		log.info("CountExternalLinkFileTool input: filePath={}", input.getFilePath());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String filePath = input.getFilePath();

			// Basic parameter validation
			if (filePath == null || filePath.trim().isEmpty()) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			return countFile(filePath);
		}
		catch (Exception e) {
			log.error("CountExternalLinkFileTool execution failed", e);
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

		// Check file type
		if (!baseOperator.isSupportedFileType(filePath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Normalize the file path
		String normalizedPath = baseOperator.normalizeFilePath(filePath);

		// Use UnifiedDirectoryManager to resolve external_link path
		UnifiedDirectoryManager directoryManager = textFileService.getUnifiedDirectoryManager();
		Path absolutePath = directoryManager.resolveAndValidateExternalLinkPath(this.rootPlanId, normalizedPath);

		if (!Files.exists(absolutePath)) {
			throw new IOException("File does not exist: " + filePath
					+ ". Please ensure the file exists in the external_link directory.");
		}

		if (!Files.isRegularFile(absolutePath)) {
			throw new IOException("Path is not a regular file: " + filePath);
		}

		return absolutePath;
	}

	/**
	 * Count lines and characters in a file
	 */
	private ToolExecuteResult countFile(String filePath) {
		try {
			Path sourceFile = validateExternalLinkPath(filePath);

			// Read all lines from the source file
			List<String> allLines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);

			// Count total lines
			int totalLines = allLines.size();

			// Count total characters (including newlines)
			long totalCharacters = Files.size(sourceFile);

			// Count characters excluding newlines (sum of all line lengths)
			int charactersWithoutNewlines = 0;
			for (String line : allLines) {
				charactersWithoutNewlines += line.length();
			}

			// Count words (split by whitespace)
			int wordCount = 0;
			for (String line : allLines) {
				if (line != null && !line.trim().isEmpty()) {
					String[] words = line.trim().split("\\s+");
					wordCount += words.length;
				}
			}

			// Build result message
			StringBuilder result = new StringBuilder();
			result.append("=".repeat(60)).append("\n");
			result.append(String.format("File Statistics for: %s\n", sourceFile.getFileName().toString()));
			result.append("=".repeat(60)).append("\n");
			result.append(String.format("Total Lines: %d\n", totalLines));
			result.append(String.format("Total Characters (including newlines): %d\n", totalCharacters));
			result.append(String.format("Total Characters (excluding newlines): %d\n", charactersWithoutNewlines));
			result.append(String.format("Total Words: %d\n", wordCount));
			result.append(String.format("File Size: %d bytes\n", totalCharacters));
			result.append("=".repeat(60));

			log.info(
					"Counted file {}: {} lines, {} characters (with newlines), {} characters (without newlines), {} words",
					filePath, totalLines, totalCharacters, charactersWithoutNewlines, wordCount);

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error counting file: {}", filePath, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error counting file: " + errorMessage);
		}
		catch (Exception e) {
			log.error("Unexpected error counting file: {}", filePath, e);
			return new ToolExecuteResult("Unexpected error counting file: " + e.getMessage());
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
		return toolI18nService.getDescription("count-external-link-file");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("count-external-link-file");
	}

	@Override
	public Class<CountFileInput> getInputType() {
		return CountFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up count file resources for plan: {}", planId);
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

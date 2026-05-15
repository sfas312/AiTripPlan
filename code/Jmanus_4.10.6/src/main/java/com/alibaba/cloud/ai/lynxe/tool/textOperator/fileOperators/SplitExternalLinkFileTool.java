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
import java.util.ArrayList;
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
 * Split file tool specifically for external_link directory. This tool splits text files
 * (markdown, code, HTML, etc.) into smaller pieces in the linked_external directory
 * (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file split operations, split file.
 */
public class SplitExternalLinkFileTool extends AbstractBaseTool<SplitExternalLinkFileTool.SplitFileInput> {

	private static final Logger log = LoggerFactory.getLogger(SplitExternalLinkFileTool.class);

	private static final String TOOL_NAME = "split-external-link-file";

	/**
	 * Default number of pieces to split file into
	 */
	private static final int DEFAULT_SPLIT_COUNT = 10;

	/**
	 * Input class for split file operations
	 */
	public static class SplitFileInput {

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		private String header;

		@com.fasterxml.jackson.annotation.JsonProperty("split_count")
		private Integer splitCount;

		// Getters and setters
		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getHeader() {
			return header;
		}

		public void setHeader(String header) {
			this.header = header;
		}

		public Integer getSplitCount() {
			return splitCount;
		}

		public void setSplitCount(Integer splitCount) {
			this.splitCount = splitCount;
		}

	}

	private final TextFileService textFileService;

	private final ToolI18nService toolI18nService;

	private final BaseFileOperator baseOperator;

	public SplitExternalLinkFileTool(TextFileService textFileService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, null) {
		};
	}

	@Override
	public ToolExecuteResult run(SplitFileInput input) {
		log.info("SplitExternalLinkFileTool input: filePath={}, splitCount={}", input.getFilePath(),
				input.getSplitCount());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String filePath = input.getFilePath();
			String header = input.getHeader();
			Integer splitCount = input.getSplitCount();

			// Basic parameter validation
			if (filePath == null || filePath.trim().isEmpty()) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			// Validate splitCount if provided
			if (splitCount != null && splitCount <= 0) {
				return new ToolExecuteResult("Error: split_count must be a positive integer");
			}

			// Use provided splitCount or default value
			int actualSplitCount = (splitCount != null) ? splitCount : DEFAULT_SPLIT_COUNT;

			return splitFile(filePath, header, actualSplitCount);
		}
		catch (Exception e) {
			log.error("SplitExternalLinkFileTool execution failed", e);
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
	 * Split file into multiple pieces
	 */
	private ToolExecuteResult splitFile(String filePath, String header, int splitCount) {
		try {
			Path sourceFile = validateExternalLinkPath(filePath);

			// Read all lines from the source file
			List<String> allLines = Files.readAllLines(sourceFile);
			if (allLines.isEmpty()) {
				return new ToolExecuteResult("Error: File is empty, cannot split");
			}

			int totalLines = allLines.size();
			int linesPerPiece = totalLines / splitCount;
			int remainder = totalLines % splitCount;

			// Prepare header (add newline if not empty)
			String headerContent = (header != null && !header.trim().isEmpty()) ? header.trim() + "\n" : "";

			// Get file name and extension
			String fileName = sourceFile.getFileName().toString();
			int lastDotIndex = fileName.lastIndexOf('.');
			String baseName = (lastDotIndex > 0) ? fileName.substring(0, lastDotIndex) : fileName;
			String extension = (lastDotIndex > 0) ? fileName.substring(lastDotIndex) : "";

			// Get parent directory for output files
			Path parentDir = sourceFile.getParent();

			List<String> createdFiles = new ArrayList<>();
			int currentLineIndex = 0;

			// Split into splitCount pieces
			for (int i = 0; i < splitCount; i++) {
				// Calculate lines for this piece (distribute remainder evenly)
				int pieceSize = linesPerPiece + (i < remainder ? 1 : 0);

				if (pieceSize == 0) {
					// Skip empty pieces if file is too small
					continue;
				}

				// Create output file name with index prefix
				String outputFileName = String.format("%d-%s%s", i, baseName, extension);
				Path outputFile = parentDir.resolve(outputFileName);

				// Prepare content for this piece
				StringBuilder content = new StringBuilder();
				if (!headerContent.isEmpty()) {
					content.append(headerContent);
				}

				// Add lines for this piece
				for (int j = 0; j < pieceSize && currentLineIndex < totalLines; j++) {
					content.append(allLines.get(currentLineIndex));
					if (currentLineIndex < totalLines - 1 || j < pieceSize - 1) {
						content.append("\n");
					}
					currentLineIndex++;
				}

				// Write the split file
				Files.writeString(outputFile, content.toString());

				createdFiles.add(outputFileName);
				log.info("Created split file {} with {} lines", outputFileName, pieceSize);
			}

			// Build result message
			StringBuilder result = new StringBuilder();
			result
				.append(String.format("Successfully split file '%s' into %d pieces:\n", fileName, createdFiles.size()));
			result.append("=".repeat(60)).append("\n");
			for (String createdFile : createdFiles) {
				result.append(String.format("  - %s\n", createdFile));
			}
			result.append(String.format("\nTotal lines in original file: %d\n", totalLines));
			result.append(String.format("Lines per piece: approximately %d\n", linesPerPiece));
			if (!headerContent.isEmpty()) {
				result.append("Header added to each split file\n");
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error splitting file: {}", filePath, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error splitting file: " + errorMessage);
		}
		catch (Exception e) {
			log.error("Unexpected error splitting file: {}", filePath, e);
			return new ToolExecuteResult("Unexpected error splitting file: " + e.getMessage());
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
		return toolI18nService.getDescription("split-external-link-file");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("split-external-link-file");
	}

	@Override
	public Class<SplitFileInput> getInputType() {
		return SplitFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up split file resources for plan: {}", planId);
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

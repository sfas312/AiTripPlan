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
 * Write file operator specifically for external_link directory. This operator creates new
 * files or overwrites existing files completely in the linked_external directory
 * (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file write operations, write file, create file.
 */
public class WriteExternalLinkFileOperator extends AbstractBaseTool<WriteExternalLinkFileOperator.WriteFileInput> {

	private static final Logger log = LoggerFactory.getLogger(WriteExternalLinkFileOperator.class);

	private static final String TOOL_NAME = "write-external-link-file-operator";

	/**
	 * Input class for write file operations
	 */
	public static class WriteFileInput {

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		@com.fasterxml.jackson.annotation.JsonProperty("contents")
		private String contents;

		// Getters and setters
		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getContents() {
			return contents;
		}

		public void setContents(String contents) {
			this.contents = contents;
		}

	}

	private final TextFileService textFileService;

	private final ToolI18nService toolI18nService;

	private final BaseFileOperator baseOperator;

	public WriteExternalLinkFileOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ShortUrlService shortUrlService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, shortUrlService) {
		};
	}

	@Override
	public ToolExecuteResult run(WriteFileInput input) {
		log.info("WriteExternalLinkFileOperator input: file_path={}", input.getFilePath());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String targetPath = input.getFilePath();
			String contents = input.getContents();

			// Basic parameter validation
			if (targetPath == null) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}
			if (contents == null) {
				return new ToolExecuteResult("Error: contents parameter is required");
			}

			// Replace short URLs in path and contents
			targetPath = baseOperator.replaceShortUrls(targetPath);
			contents = baseOperator.replaceShortUrls(contents);

			return writeFile(targetPath, contents);
		}
		catch (Exception e) {
			log.error("WriteExternalLinkFileOperator execution failed", e);
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
	 * Write file (Write tool implementation) Creates new files or overwrites existing
	 * files completely
	 */
	private ToolExecuteResult writeFile(String filePath, String contents) {
		try {
			if (contents == null) {
				return new ToolExecuteResult("Error: contents parameter is required");
			}

			Path absolutePath = validateExternalLinkPath(filePath);

			// Check if file exists before writing
			boolean fileExisted = Files.exists(absolutePath);

			// Create parent directories if they don't exist
			if (absolutePath.getParent() != null) {
				Files.createDirectories(absolutePath.getParent());
			}

			// Write contents to file (overwrites if exists, creates if doesn't exist)
			Files.writeString(absolutePath, contents);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			if (fileExisted) {
				log.info("File written (overwritten): {}", absolutePath);
				return new ToolExecuteResult("File written successfully (overwritten): " + filePath);
			}
			else {
				log.info("File written (created): {}", absolutePath);
				return new ToolExecuteResult("File written successfully (created): " + filePath);
			}
		}
		catch (IOException e) {
			log.error("Error writing file: {}", filePath, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error writing file: " + errorMessage);
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
		return toolI18nService.getDescription("write-external-link-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("write-external-link-file-operator");
	}

	@Override
	public Class<WriteFileInput> getInputType() {
		return WriteFileInput.class;
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

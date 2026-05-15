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
 * Delete file operator specifically for external_link directory. This operator deletes
 * files from the linked_external directory (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * file delete operations, delete file.
 */
public class DeleteExternalLinkFileOperator extends AbstractBaseTool<DeleteExternalLinkFileOperator.DeleteFileInput> {

	private static final Logger log = LoggerFactory.getLogger(DeleteExternalLinkFileOperator.class);

	private static final String TOOL_NAME = "delete-external-link-file-operator";

	/**
	 * Input class for delete file operations
	 */
	public static class DeleteFileInput {

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

	public DeleteExternalLinkFileOperator(TextFileService textFileService,
			SmartContentSavingService innerStorageService, ShortUrlService shortUrlService,
			ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
		this.baseOperator = new BaseFileOperator(textFileService, shortUrlService) {
		};
	}

	@Override
	public ToolExecuteResult run(DeleteFileInput input) {
		log.info("DeleteExternalLinkFileOperator input: file_path={}", input.getFilePath());
		try {
			// Set rootPlanId and currentPlanId in base operator
			baseOperator.setRootPlanId(this.rootPlanId);
			baseOperator.setCurrentPlanId(this.currentPlanId);

			String targetPath = input.getFilePath();

			// Basic parameter validation
			if (targetPath == null) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			// Replace short URLs in path
			targetPath = baseOperator.replaceShortUrls(targetPath);

			return deleteFile(targetPath);
		}
		catch (Exception e) {
			log.error("DeleteExternalLinkFileOperator execution failed", e);
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
	 * Delete a file
	 */
	private ToolExecuteResult deleteFile(String filePath) {
		try {
			Path absolutePath = validateExternalLinkPath(filePath);

			if (!Files.exists(absolutePath)) {
				return new ToolExecuteResult("Error: File does not exist: " + filePath);
			}

			Files.delete(absolutePath);

			log.info("Deleted file: {}", absolutePath);
			return new ToolExecuteResult("File deleted successfully: " + filePath);
		}
		catch (IOException e) {
			log.error("Error deleting file: {}", filePath, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error deleting file: " + errorMessage);
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
		return toolI18nService.getDescription("delete-external-link-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("delete-external-link-file-operator");
	}

	@Override
	public Class<DeleteFileInput> getInputType() {
		return DeleteFileInput.class;
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

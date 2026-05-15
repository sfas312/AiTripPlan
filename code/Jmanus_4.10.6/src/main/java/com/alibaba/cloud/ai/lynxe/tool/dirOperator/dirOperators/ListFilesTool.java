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
package com.alibaba.cloud.ai.lynxe.tool.dirOperator.dirOperators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * List files tool that lists files and directories from the local filesystem. Provides
 * access to directory listings that can be accessed across all sub-plans within the same
 * execution context.
 */
public class ListFilesTool extends AbstractBaseTool<ListFilesTool.ListFilesInput> {

	private static final Logger log = LoggerFactory.getLogger(ListFilesTool.class);

	private static final String TOOL_NAME = "list-files";

	/**
	 * Input class for list files operations
	 */
	public static class ListFilesInput {

		private String path;

		@JsonProperty("file_path")
		private String filePath;

		// Getters and setters
		public String getPath() {
			return path != null ? path : filePath;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

	}

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	private final ToolI18nService toolI18nService;

	public ListFilesTool(UnifiedDirectoryManager unifiedDirectoryManager, ToolI18nService toolI18nService) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ListFilesInput input) {
		log.info("ListFilesTool input: path={}", input.getPath());
		try {
			String directoryPath = input.getPath();
			if (directoryPath == null) {
				directoryPath = "";
			}
			return listFiles(directoryPath);
		}
		catch (Exception e) {
			log.error("ListFilesTool execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Normalize directory path by removing plan ID prefixes and relative path indicators
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
	 * List files in the plan/subplan directory
	 */
	private ToolExecuteResult listFiles(String directoryPath) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for directory operations");
			}

			// Normalize the directory path to remove plan ID prefixes
			String normalizedPath = normalizeFilePath(directoryPath != null ? directoryPath : "");

			// For list_files, always use root plan directory as the base
			Path rootPlanDirectory = unifiedDirectoryManager.getRootPlanDirectory(this.rootPlanId);

			// If a subdirectory path is provided, resolve it within root plan directory
			Path targetDirectory = rootPlanDirectory;
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {

				// Use the centralized method from UnifiedDirectoryManager
				targetDirectory = unifiedDirectoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);
			}

			// Ensure directory exists - create if needed for root plan directory
			if (!Files.exists(targetDirectory)) {
				if (normalizedPath == null || normalizedPath.isEmpty() || normalizedPath.equals(".")
						|| normalizedPath.equals("root")) {
					// Create root plan directory if it doesn't exist
					Files.createDirectories(targetDirectory);
				}
				else {
					return new ToolExecuteResult("Error: Directory does not exist: " + normalizedPath);
				}
			}

			if (!Files.isDirectory(targetDirectory)) {
				return new ToolExecuteResult("Error: Path is not a directory: " + normalizedPath);
			}

			StringBuilder result = new StringBuilder();
			result.append("Files: \n");
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {
				result.append(normalizedPath).append("\n");
			}

			java.util.List<Path> files = Files.list(targetDirectory).sorted().toList();

			if (files.isEmpty()) {
				result.append("(empty directory)\n");
			}
			else {
				for (Path path : files) {
					try {
						String fileName = path.getFileName().toString();
						if (Files.isDirectory(path)) {
							result.append(String.format("[DIR] %s/\n", fileName));
						}
						else {
							long size = Files.size(path);
							String sizeStr = formatFileSize(size);
							result.append(String.format("[FILE] %s (%s)\n", fileName, sizeStr));
						}
					}
					catch (IOException e) {
						result.append(String.format("[ERROR] %s (error reading)\n", path.getFileName()));
					}
				}
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			String pathToLog = normalizeFilePath(directoryPath != null ? directoryPath : "");
			log.error("Error listing files: {}", pathToLog, e);
			return new ToolExecuteResult("Error listing files: " + e.getMessage());
		}
	}

	/**
	 * Format file size in human-readable format
	 */
	private String formatFileSize(long size) {
		if (size < 1024)
			return size + " B";
		if (size < 1024 * 1024)
			return String.format("%.1f KB", size / 1024.0);
		if (size < 1024 * 1024 * 1024)
			return String.format("%.1f MB", size / (1024.0 * 1024));
		return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
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
		return toolI18nService.getDescription("list-files");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("list-files");
	}

	@Override
	public Class<ListFilesInput> getInputType() {
		return ListFilesInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up list files resources for plan: {}", planId);
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

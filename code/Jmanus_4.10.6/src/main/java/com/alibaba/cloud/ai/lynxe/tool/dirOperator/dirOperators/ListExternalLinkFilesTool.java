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
 * List files tool specifically for external_link directory. This tool lists files and
 * directories from the linked_external directory (external folder).
 *
 * Keywords: external files, external_link, linked_external, external folder, external
 * directory listing, list files.
 */
public class ListExternalLinkFilesTool extends AbstractBaseTool<ListExternalLinkFilesTool.ListFilesInput> {

	private static final Logger log = LoggerFactory.getLogger(ListExternalLinkFilesTool.class);

	private static final String TOOL_NAME = "list-external-link-files";

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

	public ListExternalLinkFilesTool(UnifiedDirectoryManager unifiedDirectoryManager, ToolI18nService toolI18nService) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ListFilesInput input) {
		log.info("ListExternalLinkFilesTool input: path={}", input.getPath());
		try {
			String directoryPath = input.getPath();
			if (directoryPath == null) {
				directoryPath = "";
			}
			return listFiles(directoryPath);
		}
		catch (Exception e) {
			log.error("ListExternalLinkFilesTool execution failed", e);
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
	 * List files in the external_link directory
	 */
	private ToolExecuteResult listFiles(String directoryPath) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for directory operations");
			}

			// Normalize the directory path
			String normalizedPath = normalizeFilePath(directoryPath != null ? directoryPath : "");

			// Get the external_link directory
			Path externalLinkDir = unifiedDirectoryManager.getLinkedExternalDirectory(this.rootPlanId);

			// If a subdirectory path is provided, resolve it within external_link
			// directory
			Path targetDirectory = externalLinkDir;
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {
				// Remove "linked_external/" prefix if present
				if (normalizedPath.startsWith("linked_external/")) {
					normalizedPath = normalizedPath.substring("linked_external/".length());
				}
				targetDirectory = externalLinkDir.resolve(normalizedPath).normalize();

				// Security check: ensure resolved path stays within external directory
				try {
					Path externalDirRealPath = externalLinkDir.toRealPath();
					Path resolvedRealPath = targetDirectory.toRealPath();

					if (!resolvedRealPath.startsWith(externalDirRealPath)) {
						throw new IOException("Access denied: Path is outside external directory: " + directoryPath);
					}
				}
				catch (IOException e) {
					// If toRealPath() fails, fall back to string comparison
					String externalDirStr = externalLinkDir.toString();
					String resolvedStr = targetDirectory.toString();
					if (!resolvedStr.startsWith(externalDirStr)) {
						throw new IOException("Access denied: Path is outside external directory: " + directoryPath);
					}
				}
			}

			// Ensure directory exists
			if (!Files.exists(targetDirectory)) {
				return new ToolExecuteResult("Error: Directory does not exist: " + directoryPath);
			}

			if (!Files.isDirectory(targetDirectory)) {
				return new ToolExecuteResult("Error: Path is not a directory: " + directoryPath);
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
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error listing files: " + errorMessage);
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
		return toolI18nService.getDescription("list-external-link-files");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("list-external-link-files");
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
		return "fs-ext";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

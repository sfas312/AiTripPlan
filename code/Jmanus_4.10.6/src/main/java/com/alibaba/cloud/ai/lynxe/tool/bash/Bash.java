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
package com.alibaba.cloud.ai.lynxe.tool.bash;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Bash extends AbstractBaseTool<Bash.BashInput> {

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	private final SmartContentSavingService innerStorageService;

	private static final Logger log = LoggerFactory.getLogger(Bash.class);

	/**
	 * Internal input class for defining Bash tool input parameters
	 */
	public static class BashInput {

		private String command;

		public BashInput() {
		}

		public BashInput(String command) {
			this.command = command;
		}

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

	}

	/**
	 * Unified directory manager for directory operations
	 */
	private final UnifiedDirectoryManager unifiedDirectoryManager;

	// Add operating system information
	private static final String osName = System.getProperty("os.name");

	private final String name = "bash";

	public Bash(UnifiedDirectoryManager unifiedDirectoryManager, ObjectMapper objectMapper,
			ToolI18nService toolI18nService, SmartContentSavingService innerStorageService) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
		this.innerStorageService = innerStorageService;
	}

	@Override
	public ToolExecuteResult run(BashInput input) {
		String command = input.getCommand();
		log.info("Bash command: {}", command);
		log.info("Current operating system: {}", osName);

		// Validate command paths to ensure they stay within root-plan-folder
		if (rootPlanId != null && !rootPlanId.trim().isEmpty()) {
			try {
				validateCommandPaths(command);
			}
			catch (IOException e) {
				log.warn("Command path validation failed: {}", e.getMessage());
				String errorMessage = "Error: " + e.getMessage() + ". All paths must be within the root-plan-folder.";
				// Process error message through SmartContentSavingService
				if (innerStorageService != null && rootPlanId != null && !rootPlanId.trim().isEmpty()) {
					SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
						.processContent(rootPlanId, errorMessage, "bash_path_validation_error");
					return new ToolExecuteResult(processedResult.getComprehensiveResult());
				}
				// If rootPlanId is not available, still try to process if service is
				// available
				if (innerStorageService != null) {
					SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
						.processContent("default", errorMessage, "bash_path_validation_error");
					return new ToolExecuteResult(processedResult.getComprehensiveResult());
				}
				return new ToolExecuteResult(errorMessage);
			}
		}

		List<String> commandList = new ArrayList<>();
		commandList.add(command);

		try {
			// Use ShellExecutorFactory to create executor for corresponding operating
			// system
			ShellCommandExecutor executor = ShellExecutorFactory.createExecutor();
			log.info("Using shell executor for OS: {}", osName);

			// Use root plan directory as working directory if rootPlanId is available
			// This ensures all commands execute from the root-plan-folder
			String workingDir;
			if (rootPlanId != null && !rootPlanId.trim().isEmpty()) {
				workingDir = unifiedDirectoryManager.getRootPlanDirectory(rootPlanId).toString();
				log.info("Using root plan directory as working directory: {}", workingDir);
			}
			else {
				workingDir = unifiedDirectoryManager.getWorkingDirectoryPath();
				log.warn("rootPlanId is not available, using default working directory: {}", workingDir);
			}

			List<String> result = executor.execute(commandList, workingDir);
			String resultContent = String.join("\n", result);

			// Handle empty result - return meaningful message instead of empty string
			if (resultContent == null || resultContent.trim().isEmpty()) {
				resultContent = "Command executed successfully with no output.";
			}

			// Process result through SmartContentSavingService to handle large outputs
			// Only process if rootPlanId is available (required for file saving)
			if (innerStorageService != null && rootPlanId != null && !rootPlanId.trim().isEmpty()) {
				SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
					.processContent(rootPlanId, resultContent, "bash");
				return new ToolExecuteResult(processedResult.getComprehensiveResult());
			}

			// Fallback: return JSON format if innerStorageService is not available or
			// rootPlanId is missing
			return new ToolExecuteResult(objectMapper.writeValueAsString(result));
		}
		catch (Exception e) {
			log.error("Error executing bash command", e);
			String errorMessage = "Error executing command: " + e.getMessage();
			// Process error message through SmartContentSavingService
			// Only process if rootPlanId is available (required for file saving)
			if (innerStorageService != null && rootPlanId != null && !rootPlanId.trim().isEmpty()) {
				SmartContentSavingService.SmartProcessResult processedResult = innerStorageService
					.processContent(rootPlanId, errorMessage, "bash_execution_error");
				return new ToolExecuteResult(processedResult.getComprehensiveResult());
			}
			return new ToolExecuteResult(errorMessage);
		}
	}

	/**
	 * Validate that all paths in the command stay within root-plan-folder This method
	 * checks for: 1. Absolute paths outside root-plan-folder 2. cd commands that would
	 * leave root-plan-folder 3. Paths with .. that would escape root-plan-folder
	 * @param command The command to validate
	 * @throws IOException if any path is outside root-plan-folder
	 */
	private void validateCommandPaths(String command) throws IOException {
		if (command == null || command.trim().isEmpty()) {
			return;
		}

		Path rootPlanDirectory = unifiedDirectoryManager.getRootPlanDirectory(rootPlanId);

		// Check for absolute paths that are outside root-plan-folder
		// Pattern: absolute paths starting with / but not starting with root-plan-folder
		Pattern absolutePathPattern = Pattern.compile("\\s+(/[^\\s;&|]+)");
		Matcher matcher = absolutePathPattern.matcher(command);
		while (matcher.find()) {
			String absolutePath = matcher.group(1);
			// Normalize the absolute path for comparison
			try {
				Path absPath = Paths.get(absolutePath).normalize();
				Path rootPlanPath = rootPlanDirectory.toAbsolutePath().normalize();

				// Check if absolute path is within root-plan-folder
				if (!absPath.startsWith(rootPlanPath)) {
					// Check if it's a common system path that might be safe (like /usr,
					// /bin, etc.)
					// But we should still restrict access
					if (!isSystemPath(absolutePath)) {
						throw new IOException("Access denied: Absolute path '" + absolutePath
								+ "' is outside root-plan-folder. Use relative paths from root-plan-folder instead.");
					}
				}
			}
			catch (Exception e) {
				// If path parsing fails, be conservative and reject it
				if (e instanceof IOException) {
					throw e;
				}
				log.warn("Failed to parse absolute path '{}' for validation: {}", absolutePath, e.getMessage());
			}
		}

		// Check cd command specifically
		Pattern cdPattern = Pattern.compile("\\bcd\\s+([^\\s;&|]+)", Pattern.CASE_INSENSITIVE);
		Matcher cdMatcher = cdPattern.matcher(command);
		while (cdMatcher.find()) {
			String targetPath = cdMatcher.group(1).trim();
			// Remove quotes if present
			targetPath = targetPath.replaceAll("^[\"']|[\"']$", "");

			// Skip if it's a special cd command (cd -, cd ~, etc.)
			if (targetPath.equals("-") || targetPath.equals("~") || targetPath.startsWith("~")) {
				continue;
			}

			// Validate the path using resolveAndValidatePath
			try {
				// Normalize the path (remove leading /, ./)
				String normalizedPath = normalizePath(targetPath);
				unifiedDirectoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);
				log.debug("cd command path validated: {} -> {}", targetPath, normalizedPath);
			}
			catch (IOException e) {
				throw new IOException("Access denied: cd command target '" + targetPath
						+ "' is outside root-plan-folder. " + e.getMessage());
			}
		}

		// Check for paths with .. that might escape root-plan-folder
		// This is handled by resolveAndValidatePath, but we can add explicit check
		if (command.contains("..")) {
			// Extract potential paths with ..
			Pattern dotDotPattern = Pattern.compile("([^\\s]+(?:\\.\\.)+[^\\s]*)");
			Matcher dotDotMatcher = dotDotPattern.matcher(command);
			while (dotDotMatcher.find()) {
				String pathWithDotDot = dotDotMatcher.group(1);
				// Skip if it's part of a command like "cd .." or "ls .."
				if (pathWithDotDot.matches("^\\.\\.+$")) {
					// This is just ".." or "../..", validate it
					try {
						String normalizedPath = normalizePath(pathWithDotDot);
						unifiedDirectoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);
					}
					catch (IOException e) {
						throw new IOException("Access denied: Path with '..' '" + pathWithDotDot
								+ "' would escape root-plan-folder. " + e.getMessage());
					}
				}
			}
		}
	}

	/**
	 * Normalize a path by removing leading slashes and relative indicators Similar to
	 * normalizeFilePath in GlobalFileReadOperator
	 */
	private String normalizePath(String path) {
		if (path == null || path.isEmpty()) {
			return path;
		}

		String normalized = path.trim();
		// Remove leading slashes
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		// Remove "./" prefix if present
		if (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}

		return normalized;
	}

	/**
	 * Check if a path is a system path that might be safe to access (e.g., /usr/bin,
	 * /bin, /etc - but we still restrict these)
	 */
	private boolean isSystemPath(String path) {
		// For now, we don't allow any system paths
		// All paths must be within root-plan-folder
		return false;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDescription() {
		return String.format(toolI18nService.getDescription("bash"), osName);
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("bash");
	}

	@Override
	public Class<BashInput> getInputType() {
		return BashInput.class;
	}

	@Override
	public String getServiceGroup() {
		return "default";
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String workingDir;
		if (rootPlanId != null && !rootPlanId.trim().isEmpty()) {
			// Only show root-plan directory to LLM
			workingDir = unifiedDirectoryManager.getRootPlanDirectory(rootPlanId).toString();
		}
		else {
			workingDir = unifiedDirectoryManager.getWorkingDirectoryPath();
		}

		String stateString = String.format("""
				Current Working Directory:
				%s
				""", workingDir);
		return new ToolStateInfo(getServiceGroup(), stateString);
	}

	@Override
	public void cleanup(String planId) {
		log.info("Cleaned up resources for plan: {}", planId);
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

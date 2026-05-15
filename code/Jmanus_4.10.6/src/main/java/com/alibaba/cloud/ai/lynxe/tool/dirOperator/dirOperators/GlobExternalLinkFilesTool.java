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
import java.nio.file.FileSystem;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.GitIgnoreMatcher;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.SymbolicLinkDetector;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Glob files tool specifically for external_link directory. This tool finds files
 * matching a glob pattern in the linked_external folder (external folder). Results are
 * sorted by modification time (most recently modified first).
 *
 * Keywords: external files, external_link, linked_external, external folder, glob
 * pattern, file search, find files.
 */
public class GlobExternalLinkFilesTool extends AbstractBaseTool<GlobExternalLinkFilesTool.GlobFilesInput> {

	private static final Logger log = LoggerFactory.getLogger(GlobExternalLinkFilesTool.class);

	private static final String TOOL_NAME = "glob-external-link-files";

	/**
	 * Maximum depth for directory traversal to prevent excessive recursion
	 */
	private static final int MAX_DEPTH = 100;

	/**
	 * Maximum path length to prevent path explosion issues
	 */
	private static final int MAX_PATH_LENGTH = 1000;

	/**
	 * Input class for glob files operations
	 */
	public static class GlobFilesInput {

		@JsonProperty("glob_pattern")
		private String globPattern;

		@JsonProperty("target_directory")
		private String targetDirectory;

		// Getters and setters
		public String getGlobPattern() {
			return globPattern;
		}

		public void setGlobPattern(String globPattern) {
			this.globPattern = globPattern;
		}

		public String getTargetDirectory() {
			return targetDirectory;
		}

		public void setTargetDirectory(String targetDirectory) {
			this.targetDirectory = targetDirectory;
		}

	}

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	private final ToolI18nService toolI18nService;

	private final GitIgnoreMatcher gitIgnoreMatcher;

	private final LynxeProperties lynxeProperties;

	public GlobExternalLinkFilesTool(UnifiedDirectoryManager unifiedDirectoryManager,
			SymbolicLinkDetector symlinkDetector, ToolI18nService toolI18nService, GitIgnoreMatcher gitIgnoreMatcher,
			LynxeProperties lynxeProperties) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		// Note: symlinkDetector parameter kept for backward compatibility but not used
		// This tool now explicitly skips symbolic links (like grep/ripgrep)
		this.toolI18nService = toolI18nService;
		this.gitIgnoreMatcher = gitIgnoreMatcher;
		this.lynxeProperties = lynxeProperties;
	}

	@Override
	public ToolExecuteResult run(GlobFilesInput input) {
		try {
			String globPattern = input.getGlobPattern();
			String targetDirectory = input.getTargetDirectory();

			if (globPattern == null || globPattern.isEmpty()) {
				return new ToolExecuteResult("Error: glob_pattern parameter is required");
			}

			return globFiles(globPattern, targetDirectory);
		}
		catch (Exception e) {
			log.error("GlobExternalLinkFilesTool execution failed", e);
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
	 * Get real path of a path, or return the original path if resolution fails
	 */
	private Path getRealPathOrFallback(Path path) {
		try {
			return path.toRealPath();
		}
		catch (IOException e) {
			log.warn("Cannot resolve real path for: {}, using as-is", path);
			return path;
		}
	}

	/**
	 * Determine the root path for ignore file matching. If searching within
	 * linked_external, use the actual external folder root. Otherwise, use the search
	 * root.
	 * @param searchRoot The search root path
	 * @return Root path for ignore file matching
	 */
	private Path determineIgnoreRootPath(Path searchRoot) {
		if (searchRoot == null) {
			return null;
		}

		try {
			Path normalized = searchRoot.toAbsolutePath().normalize();
			String pathString = normalized.toString();

			// Check if we're searching within linked_external directory
			if (pathString.contains("linked_external")) {
				// Find the linked_external directory in the path
				Path current = normalized;
				while (current != null && current.getNameCount() > 0) {
					if ("linked_external".equals(current.getFileName().toString())) {
						// This is the linked_external symlink, use its target as the
						// ignore root
						try {
							Path realPath = current.toRealPath();
							return realPath;
						}
						catch (IOException e) {
							return current;
						}
					}
					current = current.getParent();
				}
			}

			// Default: use search root
			return normalized;
		}
		catch (Exception e) {
			log.warn("Error determining ignore root path, using search root: {}", searchRoot, e);
			return searchRoot;
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
	 * Find files matching a glob pattern. This method searches for files matching the
	 * specified glob pattern, with results sorted by modification time (most recently
	 * modified first).
	 */
	private ToolExecuteResult globFiles(String globPattern, String targetDirectory) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for external_link glob operations");
			}

			// Normalize glob pattern: auto-prefix with **/ if not starting with **/
			String normalizedPattern = normalizeGlobPattern(globPattern);

			// Determine search root directory within external_link
			Path searchRoot;
			if (targetDirectory != null && !targetDirectory.isEmpty()) {
				// Normalize target directory path
				String normalizedTargetDir = normalizeFilePath(targetDirectory);

				// Use the centralized method from UnifiedDirectoryManager for
				// external_link paths
				searchRoot = unifiedDirectoryManager.resolveAndValidateExternalLinkPath(this.rootPlanId,
						normalizedTargetDir);

				// Check if target directory exists
				if (!Files.exists(searchRoot)) {
					return new ToolExecuteResult("Error: Target directory does not exist: " + normalizedTargetDir);
				}

				if (!Files.isDirectory(searchRoot)) {
					return new ToolExecuteResult("Error: Target path is not a directory: " + normalizedTargetDir);
				}
			}
			else {
				// Default to external_link directory root
				searchRoot = unifiedDirectoryManager.getLinkedExternalDirectory(this.rootPlanId);
			}

			// Create PathMatcher for glob pattern
			FileSystem fileSystem = FileSystems.getDefault();
			String globPatternStr = "glob:" + normalizedPattern;
			PathMatcher matcher = fileSystem.getPathMatcher(globPatternStr);

			// For patterns like **/*tools*, also check if any path component matches
			// This allows matching files in directories with "tools" in the name
			// Pattern **/*tools* should match both:
			// 1. Files with "tools" in filename: **/*tools*
			// 2. Files in directories with "tools" in name (checked manually)
			final PathMatcher directoryMatcher;
			// Extract the wildcard pattern (e.g., "tools" from "**/*tools*")
			final String wildcardPattern;
			// Check if pattern matches directory names (contains *word* where word could
			// be in directory)
			// and doesn't already have /**/ in it (which would already match directories)
			if (normalizedPattern.matches(".*\\*[^/]+\\*.*") && !normalizedPattern.contains("/**/")) {
				// Extract the wildcard pattern (e.g., "tools" from "**/*tools*")
				java.util.regex.Pattern extractPattern = java.util.regex.Pattern.compile("\\*([^/]+)\\*");
				java.util.regex.Matcher m = extractPattern.matcher(normalizedPattern);
				if (m.find()) {
					wildcardPattern = m.group(1); // e.g., "tools"
				}
				else {
					wildcardPattern = null;
				}
				// Create directory matcher: convert **/*tools* to **/*tools*/**/*
				String dirPattern = normalizedPattern.replaceAll("(\\*[^/]+\\*)", "$1/**/*");
				directoryMatcher = fileSystem.getPathMatcher("glob:" + dirPattern);
			}
			else {
				directoryMatcher = null;
				wildcardPattern = null;
			}

			// Find all matching files - NOT following symbolic links (like grep/ripgrep)
			// This avoids infinite loops from circular symlinks
			List<Path> matchingFiles = new ArrayList<>();
			Path rootPath = searchRoot;

			// Get real path of root for relativization (handles symlink root case)
			final Path rootRealPath = getRealPathOrFallback(rootPath);

			// Capture rootPlanId for use in inner class
			final String finalRootPlanId = this.rootPlanId;

			// Track visited real paths to prevent circular references when following the
			// root symlink
			Set<Path> visitedRealPaths = new HashSet<>();

			// Initialize GitIgnoreMatcher if respectGitIgnore is enabled
			boolean respectGitIgnore = lynxeProperties.getRespectGitIgnore() != null
					&& lynxeProperties.getRespectGitIgnore();
			Path ignoreRootPath = determineIgnoreRootPath(searchRoot);
			gitIgnoreMatcher.initialize(ignoreRootPath, respectGitIgnore);

			SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					// Get real path for cycle detection and comparison
					Path realPath;
					try {
						realPath = dir.toRealPath();
					}
					catch (IOException e) {
						log.warn("Cannot resolve real path for directory: {}, skipping", dir);
						return FileVisitResult.SKIP_SUBTREE;
					}

					// Check for cycles using real paths
					if (visitedRealPaths.contains(realPath)) {
						log.warn("Cycle detected: already visited {}, skipping", realPath);
						return FileVisitResult.SKIP_SUBTREE;
					}
					visitedRealPaths.add(realPath);

					// Allow the root directory even if it's a symlink (e.g.,
					// linked_external)
					// But skip other symlink directories to prevent circular references
					if (Files.isSymbolicLink(dir)) {
						// Check if this is the root (by comparing both original and real
						// paths)
						boolean isRoot = dir.equals(rootPath) || realPath.equals(rootRealPath);
						if (!isRoot) {
							return FileVisitResult.SKIP_SUBTREE;
						}
						// Root is a symlink - allow but track to prevent cycles
					}

					// Check path depth relative to root
					try {
						int depth = rootRealPath.relativize(dir.toRealPath()).getNameCount();
						if (depth > MAX_DEPTH) {
							log.warn("Path depth {} exceeds maximum ({}). Skipping directory: {}", depth, MAX_DEPTH,
									dir);
							return FileVisitResult.SKIP_SUBTREE;
						}
					}
					catch (IOException | IllegalArgumentException e) {
						log.warn("Cannot calculate depth for directory: {}, skipping", dir);
						return FileVisitResult.SKIP_SUBTREE;
					}

					// Check path length to prevent path explosion
					String pathString = dir.toString();
					if (pathString.length() > MAX_PATH_LENGTH) {
						log.warn("Path length {} exceeds maximum ({}). Skipping directory: {}", pathString.length(),
								MAX_PATH_LENGTH, dir);
						return FileVisitResult.SKIP_SUBTREE;
					}

					// Check if directory should be skipped based on ignore rules
					if (respectGitIgnore && gitIgnoreMatcher.shouldSkipDirectory(dir)) {
						return FileVisitResult.SKIP_SUBTREE;
					}

					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					// Skip symbolic links (do not follow them, like grep/ripgrep)
					if (Files.isSymbolicLink(file)) {
						return FileVisitResult.CONTINUE;
					}

					// Check path length for files as well
					String pathString = file.toString();
					if (pathString.length() > MAX_PATH_LENGTH) {
						log.warn("File path length {} exceeds maximum ({}). Skipping file: {}", pathString.length(),
								MAX_PATH_LENGTH, file);
						return FileVisitResult.CONTINUE;
					}

					// Skip if not a regular file
					if (!Files.isRegularFile(file)) {
						return FileVisitResult.CONTINUE;
					}

					// Check if file should be ignored based on ignore rules
					if (respectGitIgnore && gitIgnoreMatcher.isIgnored(file)) {
						return FileVisitResult.CONTINUE;
					}

					// Check if file matches the pattern
					// Use real paths for relativization to handle symlink root case
					// For external_link, use the external directory root
					try {
						Path fileRealPath = file.toRealPath();
						Path externalLinkDir = unifiedDirectoryManager.getLinkedExternalDirectory(finalRootPlanId);
						Path externalLinkDirRealPath = externalLinkDir.toRealPath();
						Path relativePath = externalLinkDirRealPath.relativize(fileRealPath);
						// Normalize path separators to forward slashes for consistent
						// matching
						String relativePathStr = relativePath.toString().replace('\\', '/');

						// Java's PathMatcher works on Path objects
						// Try matching with the relative path directly first
						boolean matches = matcher.matches(relativePath);

						// If that doesn't work, try with a Path created from normalized
						// string
						// This ensures consistent separator handling across platforms
						if (!matches) {
							Path normalizedRelativePath = fileSystem.getPath(relativePathStr);
							matches = matcher.matches(normalizedRelativePath);
						}

						// Also try directory matcher if available (for patterns like
						// **/*tools*)
						if (!matches && directoryMatcher != null) {
							matches = directoryMatcher.matches(relativePath);
							if (!matches) {
								Path normalizedRelativePath = fileSystem.getPath(relativePathStr);
								matches = directoryMatcher.matches(normalizedRelativePath);
							}
						}

						// Manual check: if pattern has wildcard (e.g., *tools*), check if
						// any path component contains it
						if (!matches && wildcardPattern != null) {
							// Check each component of the path
							for (Path component : relativePath) {
								if (component.toString().contains(wildcardPattern)) {
									matches = true;
									break;
								}
							}
						}

						if (matches) {
							matchingFiles.add(file);
						}
					}
					catch (IOException | IllegalArgumentException e) {
						log.warn("Cannot relativize file path: {}, skipping pattern matching", file);
						// Still try to match using the original path as fallback
						try {
							Path externalLinkDir = unifiedDirectoryManager.getLinkedExternalDirectory(finalRootPlanId);
							Path relativePath = externalLinkDir.relativize(file);
							if (matcher.matches(relativePath)) {
								matchingFiles.add(file);
							}
						}
						catch (IllegalArgumentException e2) {
							try {
								Path relativePath = rootPath.relativize(file);
								if (matcher.matches(relativePath)) {
									matchingFiles.add(file);
								}
							}
							catch (IllegalArgumentException e3) {
								log.warn("Cannot relativize file path even with fallback: {}, skipping", file);
							}
						}
					}

					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
					// Handle circular symlinks by skipping the problematic directory
					// (This should rarely happen now since we don't follow symlinks, but
					// kept for safety)
					if (exc instanceof FileSystemLoopException) {
						log.warn("Circular symlink detected: {}. Skipping this directory and continuing.", file);
						return FileVisitResult.SKIP_SUBTREE;
					}

					// Check if path is too long (might cause issues)
					String pathString = file.toString();
					if (pathString.length() > MAX_PATH_LENGTH) {
						log.warn("Path too long ({} chars): {}. Skipping and continuing.", pathString.length(), file);
						return FileVisitResult.SKIP_SUBTREE;
					}

					// For other IO errors, log and continue
					log.warn("Error accessing file/directory: {}. Skipping and continuing.", file, exc);
					return FileVisitResult.SKIP_SUBTREE;
				}
			};

			// Walk the directory tree with depth limit
			// Follow links to allow root symlink (e.g., linked_external) but cycle
			// detection
			// prevents infinite loops from circular symlinks
			Files.walkFileTree(searchRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, visitor);

			// Sort by modification time (most recently modified first)
			matchingFiles.sort(Comparator.comparing((Path path) -> {
				try {
					FileTime lastModified = Files.getLastModifiedTime(path);
					return lastModified.toInstant();
				}
				catch (IOException e) {
					log.warn("Error getting modification time for file: {}", path, e);
					return java.time.Instant.EPOCH;
				}
			}).reversed());

			// Build result
			StringBuilder result = new StringBuilder();
			result.append(String.format("Glob results for pattern '%s':\n", globPattern));
			if (targetDirectory != null && !targetDirectory.isEmpty()) {
				result.append(String.format("Search directory: %s\n", normalizeFilePath(targetDirectory)));
			}
			result.append("=".repeat(60)).append("\n");

			if (matchingFiles.isEmpty()) {
				result.append("No files found matching the pattern.\n");
			}
			else {
				result.append(String.format("Found %d file(s):\n\n", matchingFiles.size()));
				for (Path path : matchingFiles) {
					try {
						// Use real paths for relativization to handle symlink root case
						// For external_link, use the external directory root
						Path relativePath;
						try {
							Path externalLinkDir = unifiedDirectoryManager.getLinkedExternalDirectory(this.rootPlanId);
							Path externalLinkDirRealPath = externalLinkDir.toRealPath();
							Path fileRealPath = path.toRealPath();
							relativePath = externalLinkDirRealPath.relativize(fileRealPath);
						}
						catch (IOException | IllegalArgumentException e) {
							// Fallback to original path if real path resolution fails
							try {
								Path externalLinkDir = unifiedDirectoryManager
									.getLinkedExternalDirectory(this.rootPlanId);
								relativePath = externalLinkDir.relativize(path);
							}
							catch (IllegalArgumentException e2) {
								relativePath = rootPath.relativize(path);
							}
						}
						String relativePathStr = relativePath.toString().replace('\\', '/');
						long size = Files.size(path);
						String sizeStr = formatFileSize(size);
						FileTime lastModified = Files.getLastModifiedTime(path);
						result.append(String.format("%s (%s, modified: %s)\n", relativePathStr, sizeStr,
								lastModified.toString()));
					}
					catch (IOException e) {
						log.warn("Error reading file info: {}", path, e);
						// Try to get relative path for display even if file info read
						// fails
						try {
							Path relativePath;
							try {
								Path externalLinkDir = unifiedDirectoryManager
									.getLinkedExternalDirectory(this.rootPlanId);
								Path externalLinkDirRealPath = externalLinkDir.toRealPath();
								Path fileRealPath = path.toRealPath();
								relativePath = externalLinkDirRealPath.relativize(fileRealPath);
							}
							catch (IOException | IllegalArgumentException e2) {
								try {
									Path externalLinkDir = unifiedDirectoryManager
										.getLinkedExternalDirectory(this.rootPlanId);
									relativePath = externalLinkDir.relativize(path);
								}
								catch (IllegalArgumentException e3) {
									relativePath = rootPath.relativize(path);
								}
							}
							String relativePathStr = relativePath.toString().replace('\\', '/');
							result.append(String.format("%s (error reading file info)\n", relativePathStr));
						}
						catch (IllegalArgumentException e2) {
							result.append(String.format("%s (error reading file info)\n", path.getFileName()));
						}
					}
				}
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error performing glob search: pattern={}, targetDirectory={}", globPattern, targetDirectory, e);
			String errorMessage = e.getMessage();

			// Provide more helpful error message if external_link is not configured
			if (errorMessage != null && errorMessage.contains("External linked folder is not configured")) {
				return new ToolExecuteResult("Error: External linked folder is not configured. "
						+ "Please configure 'lynxe.general.externalLinkedFolder' in system settings before using external_link file operators. "
						+ "Original error: " + errorMessage);
			}

			return new ToolExecuteResult("Error performing glob search: " + errorMessage);
		}
	}

	/**
	 * Normalize glob pattern by auto-prefixing with recursive pattern if needed. Patterns
	 * are automatically prefixed for recursive search.
	 */
	private String normalizeGlobPattern(String globPattern) {
		if (globPattern == null || globPattern.isEmpty()) {
			return globPattern;
		}

		String trimmed = globPattern.trim();

		// If pattern doesn't start with **/, prepend it for recursive search
		if (!trimmed.startsWith("**/")) {
			// Handle patterns that start with / (absolute-like patterns)
			if (trimmed.startsWith("/")) {
				trimmed = trimmed.substring(1);
			}
			return "**/" + trimmed;
		}

		return trimmed;
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
		return toolI18nService.getDescription("glob-external-link-files");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("glob-external-link-files");
	}

	@Override
	public Class<GlobFilesInput> getInputType() {
		return GlobFilesInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up glob files resources for plan: {}", planId);
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

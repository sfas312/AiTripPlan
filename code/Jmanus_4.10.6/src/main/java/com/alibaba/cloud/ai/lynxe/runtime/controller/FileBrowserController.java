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
package com.alibaba.cloud.ai.lynxe.runtime.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cloud.ai.lynxe.tool.filesystem.SymbolicLinkDetector;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

@RestController
@RequestMapping("/api/file-browser")
@CrossOrigin(origins = "*")
public class FileBrowserController {

	private static final Logger logger = LoggerFactory.getLogger(FileBrowserController.class);

	@Autowired
	private UnifiedDirectoryManager directoryManager;

	@Autowired
	private SymbolicLinkDetector symlinkDetector;

	/**
	 * File tree node representation
	 */
	public static class FileNode {

		private String name;

		private String path;

		private String type; // "file" or "directory"

		private long size;

		private String lastModified;

		private List<FileNode> children;

		public FileNode() {
		}

		public FileNode(String name, String path, String type, long size, String lastModified) {
			this.name = name;
			this.path = path;
			this.type = type;
			this.size = size;
			this.lastModified = lastModified;
			this.children = new ArrayList<>();
		}

		// Getters and setters
		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public long getSize() {
			return size;
		}

		public void setSize(long size) {
			this.size = size;
		}

		public String getLastModified() {
			return lastModified;
		}

		public void setLastModified(String lastModified) {
			this.lastModified = lastModified;
		}

		public List<FileNode> getChildren() {
			return children;
		}

		public void setChildren(List<FileNode> children) {
			this.children = children;
		}

	}

	/**
	 * Get file tree for a specific plan ID
	 * @param planId The plan ID
	 * @return File tree structure
	 */
	@GetMapping("/tree/{planId}")
	public ResponseEntity<?> getFileTree(@PathVariable("planId") String planId) {
		try {
			Path planDir = directoryManager.getRootPlanDirectory(planId);

			if (!Files.exists(planDir)) {
				return ResponseEntity
					.ok(Map.of("success", false, "message", "Plan directory not found for planId: " + planId));
			}

			FileNode rootNode = buildFileTree(planDir, planId);

			// Print simple tree structure for debugging
			logger.info("File tree for planId {}:\n{}", planId, printTree(rootNode, ""));

			return ResponseEntity.ok(Map.of("success", true, "data", rootNode));

		}
		catch (Exception e) {
			logger.error("Error getting file tree for planId: {}", planId, e);
			return ResponseEntity.internalServerError()
				.body(Map.of("success", false, "message", "Error retrieving file tree: " + e.getMessage()));
		}
	}

	/**
	 * Get file content
	 * @param planId The plan ID
	 * @param filePath The relative file path
	 * @return File content
	 */
	@GetMapping("/content/{planId}")
	public ResponseEntity<?> getFileContent(@PathVariable("planId") String planId,
			@RequestParam("path") String filePath) {
		try {
			Path planDir = directoryManager.getRootPlanDirectory(planId);
			Path targetFile = planDir.resolve(filePath).normalize();

			// Security check: ensure the file is within the plan directory
			if (!targetFile.startsWith(planDir)) {
				return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "Access denied: File path is outside plan directory"));
			}

			if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
				return ResponseEntity.notFound().build();
			}

			String mimeType = null;
			try {
				mimeType = Files.probeContentType(targetFile);
			}
			catch (Exception e) {
				logger.debug("Failed to probe content type for file: {}, will determine from extension", targetFile);
			}

			String fileName = targetFile.getFileName() != null ? targetFile.getFileName().toString().toLowerCase() : "";

			if (mimeType == null) {
				// Try to determine MIME type from file extension
				if (fileName.endsWith(".png")) {
					mimeType = "image/png";
				}
				else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
					mimeType = "image/jpeg";
				}
				else if (fileName.endsWith(".gif")) {
					mimeType = "image/gif";
				}
				else if (fileName.endsWith(".svg")) {
					mimeType = "image/svg+xml";
				}
				else if (fileName.endsWith(".webp")) {
					mimeType = "image/webp";
				}
				else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
					mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
				}
				else if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
					mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
				}
				else if (fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
					mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
				}
				else if (fileName.endsWith(".pdf")) {
					mimeType = "application/pdf";
				}
				else if (fileName.endsWith(".zip")) {
					mimeType = "application/zip";
				}
				else {
					mimeType = "application/octet-stream";
				}
			}

			// Ensure mimeType is not null (defensive check)
			if (mimeType == null || mimeType.isEmpty()) {
				mimeType = "application/octet-stream";
			}

			// Check if it's a binary file that should be downloaded instead of displayed
			// This includes: images, videos, audio, Office documents, PDFs, archives,
			// etc.
			boolean isBinaryFile = mimeType.startsWith("image/") || mimeType.startsWith("video/")
					|| mimeType.startsWith("audio/") || mimeType.equals("application/octet-stream")
					|| mimeType.equals("application/pdf") || mimeType.equals("application/zip")
					|| mimeType.equals("application/x-zip-compressed")
					|| mimeType.startsWith("application/vnd.openxmlformats-officedocument")
					|| mimeType.startsWith("application/vnd.ms-") || mimeType.startsWith("application/msword")
					|| mimeType.startsWith("application/vnd.ms-excel")
					|| mimeType.startsWith("application/vnd.ms-powerpoint");

			// Check if it's a downloadable-only file (Office docs, PDFs, archives)
			// These should not be Base64 encoded for display, but should be downloaded
			boolean isDownloadOnly = mimeType.startsWith("application/vnd.openxmlformats-officedocument")
					|| mimeType.startsWith("application/vnd.ms-") || mimeType.startsWith("application/msword")
					|| mimeType.startsWith("application/vnd.ms-excel")
					|| mimeType.startsWith("application/vnd.ms-powerpoint") || mimeType.equals("application/pdf")
					|| mimeType.equals("application/zip") || mimeType.equals("application/x-zip-compressed");

			Object content;
			if (isDownloadOnly) {
				// For download-only files (Office docs, PDFs, archives), return a special
				// flag
				// Frontend should handle these by triggering a download instead of
				// displaying
				content = null; // No content, frontend should download
			}
			else if (isBinaryFile) {
				// For binary files that can be displayed (images), encode to Base64
				byte[] fileBytes = Files.readAllBytes(targetFile);
				content = Base64.getEncoder().encodeToString(fileBytes);
			}
			else {
				// For text files, read as string
				try {
					content = Files.readString(targetFile);
				}
				catch (java.nio.charset.MalformedInputException e) {
					// If text reading fails, treat as binary file
					logger.warn("Failed to read file as text (likely binary), treating as download-only: {}", filePath);
					content = null; // No content, frontend should download
					isDownloadOnly = true;
				}
			}

			long fileSize = 0;
			try {
				fileSize = Files.size(targetFile);
			}
			catch (IOException e) {
				logger.warn("Failed to get file size for: {}, using 0", targetFile);
			}

			return ResponseEntity.ok(Map.of("success", true, "data",
					Map.of("content", content != null ? content : "", "mimeType",
							mimeType != null ? mimeType : "text/plain", "size", fileSize, "isBinary", isBinaryFile,
							"downloadOnly", isDownloadOnly)));

		}
		catch (java.nio.file.NoSuchFileException e) {
			logger.warn("File not found: planId={}, path={}", planId, filePath);
			return ResponseEntity.status(404).body(Map.of("success", false, "message", "File not found: " + filePath));
		}
		catch (java.nio.file.AccessDeniedException e) {
			logger.warn("Access denied: planId={}, path={}", planId, filePath);
			return ResponseEntity.status(403)
				.body(Map.of("success", false, "message", "Access denied: " + e.getMessage()));
		}
		catch (Exception e) {
			logger.error("Error reading file content for planId: {}, path: {}", planId, filePath, e);
			// For download-only files, if there's an error reading content, still return
			// success
			// but mark as download-only so frontend can download it
			String fileName = filePath.toLowerCase();
			boolean isLikelyDownloadOnly = fileName.endsWith(".docx") || fileName.endsWith(".doc")
					|| fileName.endsWith(".xlsx") || fileName.endsWith(".xls") || fileName.endsWith(".pptx")
					|| fileName.endsWith(".ppt") || fileName.endsWith(".pdf") || fileName.endsWith(".zip");

			if (isLikelyDownloadOnly) {
				logger.info("Treating file as download-only due to read error: {}", filePath);
				return ResponseEntity.ok(Map.of("success", true, "data", Map.of("content", "", "mimeType",
						"application/octet-stream", "size", 0L, "isBinary", true, "downloadOnly", true)));
			}

			return ResponseEntity.internalServerError()
				.body(Map.of("success", false, "message", "Error reading file: " + e.getMessage()));
		}
	}

	/**
	 * Download file
	 * @param planId The plan ID
	 * @param filePath The relative file path
	 * @return File download response
	 */
	@GetMapping("/download/{planId}")
	public ResponseEntity<Resource> downloadFile(@PathVariable("planId") String planId,
			@RequestParam("path") String filePath) {
		try {
			Path planDir = directoryManager.getRootPlanDirectory(planId);
			Path targetFile = planDir.resolve(filePath).normalize();

			// Security check: ensure the file is within the plan directory
			if (!targetFile.startsWith(planDir)) {
				return ResponseEntity.badRequest().build();
			}

			if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
				return ResponseEntity.notFound().build();
			}

			Resource resource = new FileSystemResource(targetFile);
			String mimeType = Files.probeContentType(targetFile);
			if (mimeType == null) {
				mimeType = "application/octet-stream";
			}

			// Check if it's an image file - serve inline for images, attachment for
			// others
			boolean isImage = mimeType != null && mimeType.startsWith("image/");
			ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(mimeType));

			if (isImage) {
				// Serve images inline so they can be displayed in browser
				responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION,
						"inline; filename=\"" + targetFile.getFileName().toString() + "\"");
			}
			else {
				// Serve other files as attachments
				responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + targetFile.getFileName().toString() + "\"");
			}

			return responseBuilder.body(resource);

		}
		catch (Exception e) {
			logger.error("Error downloading file for planId: {}, path: {}", planId, filePath, e);
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Build file tree recursively with symbolic link cycle detection
	 */
	private FileNode buildFileTree(Path directory, String planId) throws IOException {
		String relativePath = "";
		Path planDir = directoryManager.getRootPlanDirectory(planId);

		if (!directory.equals(planDir)) {
			relativePath = planDir.relativize(directory).toString();
		}

		FileNode node = new FileNode(directory.getFileName() != null ? directory.getFileName().toString() : planId,
				relativePath, "directory", 0, Files.getLastModifiedTime(directory).toString());

		try (Stream<Path> children = Files.list(directory)) {
			children.sorted((a, b) -> {
				// Directories first, then files
				boolean aIsDir = Files.isDirectory(a);
				boolean bIsDir = Files.isDirectory(b);
				if (aIsDir && !bIsDir)
					return -1;
				if (!aIsDir && bIsDir)
					return 1;
				return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
			}).forEach(child -> {
				try {
					// Check if child still exists (may have been deleted during
					// traversal)
					if (!Files.exists(child)) {
						logger.debug("Skipping deleted file/directory: {}", child);
						return;
					}

					// Check if it's a symbolic link
					boolean isSymlink = false;
					try {
						isSymlink = Files.isSymbolicLink(child);
					}
					catch (Exception e) {
						// May throw SecurityException or other exceptions
						logger.debug("Error checking if path is symbolic link: {}, treating as regular file", child);
					}

					if (isSymlink) {
						// Special handling for linked_external: show it but don't
						// traverse it
						// This prevents infinite loops while still allowing users to see
						// the link
						String fileName = child.getFileName().toString();
						if ("linked_external".equals(fileName)) {
							// Show linked_external as a directory node but don't traverse
							// it
							String childRelativePath = planDir.relativize(child).toString();
							FileNode symlinkNode = new FileNode(fileName, childRelativePath, "directory", 0,
									Files.getLastModifiedTime(child).toString());
							// Add a placeholder child to indicate it's a symlink
							FileNode placeholder = new FileNode("(symbolic link - not traversed)", "", "file", 0, "");
							symlinkNode.getChildren().add(placeholder);
							node.getChildren().add(symlinkNode);
							logger.debug("Added linked_external symlink node (not traversed): {}", child);
							return;
						}

						// Check for circular reference for other symlinks (may fail if
						// symlink was deleted)
						// Note: isCircularReference catches IOException internally, so we
						// don't need to catch it here
						if (symlinkDetector.isCircularReference(child, planDir)) {
							String symlinkInfo = "unknown";
							try {
								symlinkInfo = symlinkDetector.getSymlinkInfo(child);
							}
							catch (Exception e) {
								symlinkInfo = child + " (error getting info: " + e.getMessage() + ")";
							}
							logger.warn("Skipping circular symlink in file tree: {}", symlinkInfo);
							return;
						}
						// Log symlink info for debugging (may fail if symlink was
						// deleted)
						try {
							logger.debug("Following safe symlink: {}", symlinkDetector.getSymlinkInfo(child));
						}
						catch (Exception e) {
							logger.debug("Following symlink: {} (unable to get detailed info: {})", child,
									e.getMessage());
						}
					}

					// Check if it's a directory (may fail if file was deleted)
					boolean isDirectory = false;
					try {
						isDirectory = Files.isDirectory(child);
					}
					catch (Exception e) {
						// May throw SecurityException or other exceptions
						logger.debug("Error checking if path is directory: {}, skipping", child);
						return;
					}

					if (isDirectory) {
						node.getChildren().add(buildFileTree(child, planId));
					}
					else {
						String childRelativePath = planDir.relativize(child).toString();
						FileNode fileNode = new FileNode(child.getFileName().toString(), childRelativePath, "file",
								Files.size(child), Files.getLastModifiedTime(child).toString());
						node.getChildren().add(fileNode);
					}
				}
				catch (java.nio.file.NoSuchFileException e) {
					// File/directory was deleted during traversal, skip it
					logger.debug("Skipping deleted file/directory during traversal: {}", child);
				}
				catch (IOException e) {
					logger.warn("Error processing file: {}", child, e);
				}
			});
		}

		return node;
	}

	/**
	 * Print a simple tree structure for debugging
	 */
	private String printTree(FileNode node, String indent) {
		StringBuilder sb = new StringBuilder();
		sb.append(indent).append(node.getName());
		if ("file".equals(node.getType())) {
			sb.append(" (").append(node.getSize()).append(" bytes)");
		}
		else if ("directory".equals(node.getType())) {
			sb.append("/");
		}
		sb.append("\n");

		if (node.getChildren() != null && !node.getChildren().isEmpty()) {
			for (FileNode child : node.getChildren()) {
				sb.append(printTree(child, indent + "  "));
			}
		}

		return sb.toString();
	}

}

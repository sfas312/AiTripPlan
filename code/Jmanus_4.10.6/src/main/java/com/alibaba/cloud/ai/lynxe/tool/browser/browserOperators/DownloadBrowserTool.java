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
package com.alibaba.cloud.ai.lynxe.tool.browser.browserOperators;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.BrowserUseCommonService;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * Download browser tool that clicks download link and saves file to specified directory.
 */
public class DownloadBrowserTool extends AbstractBrowserTool<DownloadBrowserTool.DownloadInput> {

	private static final Logger log = LoggerFactory.getLogger(DownloadBrowserTool.class);

	private static final String TOOL_NAME = "download-browser";

	private final ToolI18nService toolI18nService;

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	/**
	 * Input class for download operations
	 */
	public static class DownloadInput {

		private Integer index;

		// Getters and setters
		public Integer getIndex() {
			return index;
		}

		public void setIndex(Integer index) {
			this.index = index;
		}

	}

	public DownloadBrowserTool(BrowserUseCommonService browserUseTool, UnifiedDirectoryManager unifiedDirectoryManager,
			ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(DownloadInput input) {
		log.info("DownloadBrowserTool request: index={}", input.getIndex());
		try {
			ToolExecuteResult validation = validateDriver();
			if (validation != null) {
				return validation;
			}

			Integer index = input.getIndex();
			if (index == null) {
				return new ToolExecuteResult("Error: index parameter is required");
			}

			// Get download directory for current plan
			Path downloadDir = unifiedDirectoryManager.getRootPlanDirectory(getRootPlanId()).resolve("downloads");
			try {
				unifiedDirectoryManager.ensureDirectoryExists(downloadDir);
			}
			catch (java.io.IOException e) {
				log.error("Failed to create download directory: {}", e.getMessage());
				return new ToolExecuteResult("Failed to create download directory: " + e.getMessage());
			}

			return executeActionWithRetry(() -> {
				Page page = getCurrentPage();
				if (page == null) {
					return new ToolExecuteResult("No active page available");
				}

				try {
					// Create a future to capture the download
					CompletableFuture<Download> downloadFuture = new CompletableFuture<>();

					// Set up download listener
					page.onDownload(download -> {
						log.info("Download started: {}", download.suggestedFilename());
						downloadFuture.complete(download);
					});

					// Get the element locator by index
					var locator = getLocatorByIdx(index);
					if (locator == null) {
						return new ToolExecuteResult("Element with index " + index + " not found");
					}

					// Check if element exists
					if (locator.count() == 0) {
						return new ToolExecuteResult("Element with index " + index + " does not exist");
					}

					// Click the download element
					log.info("Clicking element with index {} to trigger download", index);
					locator.first()
						.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(getElementTimeoutMs()));

					// Wait for download to start (with timeout)
					Download download;
					try {
						download = downloadFuture.get(getBrowserTimeoutSec(), TimeUnit.SECONDS);
					}
					catch (java.util.concurrent.TimeoutException e) {
						log.warn("No download was triggered within timeout period");
						return new ToolExecuteResult("No download was triggered after clicking element with index "
								+ index
								+ ". The element might not be a download link or the download might have been blocked.");
					}

					// Get suggested filename
					String suggestedFilename = download.suggestedFilename();
					log.info("Download detected: {}", suggestedFilename);

					// Save the download to the download directory
					Path downloadPath = downloadDir.resolve(suggestedFilename);
					download.saveAs(downloadPath);

					// Wait for download to complete
					String failure = download.failure();
					if (failure != null) {
						log.error("Download failed: {}", failure);
						return new ToolExecuteResult("Download failed: " + failure);
					}

					log.info("Download completed successfully: {}", downloadPath);

					// Get file size
					long fileSize = java.nio.file.Files.size(downloadPath);
					String fileSizeStr = formatFileSize(fileSize);

					return new ToolExecuteResult(String.format(
							"File downloaded successfully:\n" + "- Filename: %s\n" + "- Size: %s\n" + "- Location: %s",
							suggestedFilename, fileSizeStr, downloadPath.toString()));

				}
				catch (Exception e) {
					log.error("Error during download action: {}", e.getMessage(), e);
					return new ToolExecuteResult("Download failed: " + e.getMessage());
				}
			}, "download");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing download: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser download timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing download: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser download failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing download: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser download failed: " + e.getMessage());
		}
	}

	/**
	 * Format file size to human-readable format
	 */
	private String formatFileSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		else if (bytes < 1024 * 1024) {
			return String.format("%.2f KB", bytes / 1024.0);
		}
		else if (bytes < 1024 * 1024 * 1024) {
			return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
		}
		else {
			return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("download-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("download-browser");
	}

	@Override
	public Class<DownloadInput> getInputType() {
		return DownloadInput.class;
	}

	@Override
	public String getServiceGroup() {
		return "bw";
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String stateString = browserUseTool.getCurrentToolStateString(getCurrentPlanId(), getRootPlanId());
		return new ToolStateInfo("bw", stateString);
	}

}

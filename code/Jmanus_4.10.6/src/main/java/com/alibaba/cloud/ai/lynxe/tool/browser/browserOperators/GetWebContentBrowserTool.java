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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.BrowserUseCommonService;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.TextFileService;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * Get web content browser tool that gets current page ARIA snapshot content.
 */
public class GetWebContentBrowserTool extends AbstractBrowserTool<GetWebContentBrowserTool.GetWebContentInput> {

	private static final Logger log = LoggerFactory.getLogger(GetWebContentBrowserTool.class);

	private static final String TOOL_NAME = "get-web-content-browser";

	private final ToolI18nService toolI18nService;

	private final TextFileService textFileService;

	/**
	 * Input class for get_web_content operations
	 */
	public static class GetWebContentInput {

		// No parameters needed for get_web_content

	}

	public GetWebContentBrowserTool(BrowserUseCommonService browserUseTool, TextFileService textFileService,
			ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.textFileService = textFileService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(GetWebContentInput input) {
		log.info("GetWebContentBrowserTool request");
		try {
			ToolExecuteResult validation = validateDriver();
			if (validation != null) {
				return validation;
			}

			return executeActionWithRetry(() -> {
				log.info("Writing current web content to file");
				// Get current page
				Page currentPage = getCurrentPage();
				if (currentPage == null || currentPage.isClosed()) {
					return new ToolExecuteResult("Error: Current page is not available");
				}

				// Get current page state
				Map<String, Object> state = browserUseTool.getCurrentState(currentPage, getRootPlanId());

				// Get page title from state
				String title = (String) state.get("title");
				if (title == null || title.trim().isEmpty() || "unknown".equals(title)) {
					title = "untitled";
				}

				// Sanitize title to create valid filename
				String sanitizedTitle = sanitizeFileName(title);
				// Generate random number (3-4 digits: 100-9999) to append to filename
				int randomNumber = ThreadLocalRandom.current().nextInt(100, 10000);
				String fileName = sanitizedTitle + "_" + randomNumber + ".yaml";

				// Get directory path - use rootPlanId if available, otherwise use
				// currentPlanId
				String rootPlanId = getRootPlanId();

				if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
					return new ToolExecuteResult("Error: Root Plan ID is not available");
				}

				// Use TextFileService to get the root plan directory
				// Similar to GlobalFileOperator, files are stored in rootPlanId/
				Path rootPlanDirectory = textFileService.getRootPlanDirectory(rootPlanId);

				// Ensure root plan directory exists
				Files.createDirectories(rootPlanDirectory);

				// Create file path within root plan directory
				Path filePath = rootPlanDirectory.resolve(fileName).normalize();

				// Verify the path stays within the root plan directory (security check)
				if (!filePath.startsWith(rootPlanDirectory)) {
					return new ToolExecuteResult("Error: File path is outside the root plan directory");
				}

				// Format state content as YAML-like string
				StringBuilder content = new StringBuilder();
				content.append("# Title: ").append(state.get("title")).append("\n");
				content.append("\n");

				// Add tabs information
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> tabs = (List<Map<String, Object>>) state.get("tabs");
				if (tabs != null && !tabs.isEmpty()) {
					content.append("# Available Tabs:\n");
					for (int i = 0; i < tabs.size(); i++) {
						Map<String, Object> tab = tabs.get(i);
						content.append("#   [")
							.append(i)
							.append("] ")
							.append(tab.get("title"))
							.append(": ")
							.append(tab.get("url"))
							.append("\n");
					}
					content.append("\n");
				}

				// Add interactive elements (ARIA snapshot)
				Object interactiveElements = state.get("interactive_elements");
				if (interactiveElements != null) {
					content.append("# Interactive Elements:\n");
					content.append(interactiveElements.toString());
				}

				// Write to file
				Files.writeString(filePath, content.toString(), StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

				log.info("Successfully wrote page state to file: {}", filePath);
				// Return relative path (just the filename) instead of absolute path
				return new ToolExecuteResult("Successfully wrote page state to file: " + fileName);
			}, "get_web_content");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing get_web_content: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser get_web_content timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing get_web_content: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser get_web_content failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing get_web_content: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser get_web_content failed: " + e.getMessage());
		}
	}

	/**
	 * Sanitize a string to create a valid filename
	 * @param fileName The original filename string
	 * @return Sanitized filename
	 */
	private String sanitizeFileName(String fileName) {
		if (fileName == null || fileName.trim().isEmpty()) {
			return "untitled";
		}

		// Remove or replace invalid characters for filenames
		String sanitized = fileName.trim()
			.replaceAll("[<>:\"/\\|?*]", "_") // Replace invalid characters with
												// underscore
			.replaceAll("\\s+", "_") // Replace whitespace with underscore
			.replaceAll("_{2,}", "_") // Replace multiple underscores with single
										// underscore
			.replaceAll("^_+|_+$", ""); // Remove leading/trailing underscores

		// Limit length to avoid filesystem issues
		if (sanitized.length() > 200) {
			sanitized = sanitized.substring(0, 200);
		}

		// Ensure it's not empty after sanitization
		if (sanitized.isEmpty()) {
			sanitized = "untitled";
		}

		return sanitized;
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("get-web-content-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("get-web-content-browser");
	}

	@Override
	public Class<GetWebContentInput> getInputType() {
		return GetWebContentInput.class;
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

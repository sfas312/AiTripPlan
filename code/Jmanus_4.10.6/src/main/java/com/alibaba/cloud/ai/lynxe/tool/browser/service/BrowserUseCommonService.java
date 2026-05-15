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
package com.alibaba.cloud.ai.lynxe.tool.browser.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.TextFileService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

@Service
public class BrowserUseCommonService {

	private static final Logger log = LoggerFactory.getLogger(BrowserUseCommonService.class);

	private final ChromeDriverService chromeDriverService;

	private final com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService;

	public BrowserUseCommonService(ChromeDriverService chromeDriverService,
			SmartContentSavingService innerStorageService, ObjectMapper objectMapper,
			com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService shortUrlService, TextFileService textFileService,
			ToolI18nService toolI18nService, UnifiedDirectoryManager unifiedDirectoryManager) {
		this.chromeDriverService = chromeDriverService;
		this.shortUrlService = shortUrlService;
	}

	/**
	 * Get driver for a specific plan
	 * @param planId the plan ID
	 * @return DriverWrapper instance
	 */
	public DriverWrapper getDriver(String planId) {
		try {
			DriverWrapper driver = chromeDriverService.getDriver(planId);
			if (driver == null) {
				throw new RuntimeException("Failed to get driver for planId: " + planId);
			}
			return driver;
		}
		catch (Exception e) {
			log.error("Error getting driver for planId {}: {}", planId, e.getMessage(), e);
			throw new RuntimeException("Failed to get driver for planId: " + planId, e);
		}
	}

	/**
	 * Get browser operation timeout configuration
	 * @return Timeout in seconds, returns default value of 30 seconds if not configured
	 */
	private Integer getBrowserTimeout() {
		Integer timeout = getLynxeProperties().getBrowserRequestTimeout();
		return timeout != null ? timeout : 30; // Default timeout is 30 seconds
	}

	/**
	 * Get ShortUrlService instance
	 * @return ShortUrlService
	 */
	public com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService getShortUrlService() {
		return shortUrlService;
	}

	private List<Map<String, Object>> getTabsInfo(Page page) {
		try {
			// Filter out closed pages to avoid including history
			return page.context().pages().stream().filter(p -> {
				try {
					return !p.isClosed();
				}
				catch (Exception e) {
					log.debug("Error checking if page is closed: {}", e.getMessage());
					return false;
				}
			}).map(p -> {
				Map<String, Object> tabInfo = new HashMap<>();
				try {
					tabInfo.put("url", p.url());
					tabInfo.put("title", p.title());
				}
				catch (PlaywrightException e) {
					log.warn("Failed to get tab info: {}", e.getMessage());
					tabInfo.put("url", "error: " + e.getMessage());
					tabInfo.put("title", "error: " + e.getMessage());
				}
				catch (Exception e) {
					log.warn("Unexpected error getting tab info: {}", e.getMessage());
					tabInfo.put("url", "error: " + e.getMessage());
					tabInfo.put("title", "error: " + e.getMessage());
				}
				return tabInfo;
			}).toList();
		}
		catch (PlaywrightException e) {
			log.warn("Failed to get pages from context: {}", e.getMessage());
			return List.of(Map.of("error", "Failed to get tabs: " + e.getMessage()));
		}
		catch (Exception e) {
			log.warn("Unexpected error getting tabs info: {}", e.getMessage());
			return List.of(Map.of("error", "Failed to get tabs: " + e.getMessage()));
		}
	}

	/**
	 * Get current browser state for a page
	 * @param page the Playwright Page instance
	 * @param rootPlanId the root plan ID for short URL resolution
	 * @return Map containing browser state information
	 */
	public Map<String, Object> getCurrentState(Page page, String rootPlanId) {
		Map<String, Object> state = new HashMap<>();

		try {
			// Validate page first
			if (page == null) {
				state.put("error", "Page is null");
				return state;
			}

			if (page.isClosed()) {
				state.put("error", "Page is closed");
				return state;
			}

			// Wait for page to load completely to avoid context destruction errors when
			// getting information during navigation
			try {
				Integer timeout = getBrowserTimeout();
				// First wait for DOM content loaded
				page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
						new Page.WaitForLoadStateOptions().setTimeout(timeout * 1000));
				// Then wait for network idle to ensure all AJAX requests and dynamic
				// content updates are complete
				// This is especially important after actions like key_enter that trigger
				// searches
				try {
					page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
							new Page.WaitForLoadStateOptions().setTimeout(3000)); // 3
					// second
					// timeout
					// for
					// network
					// idle
				}
				catch (TimeoutError e) {
					// If network idle timeout, wait a bit more for dynamic content to
					// update
					log.debug("Network idle timeout, waiting for content updates: {}", e.getMessage());
					Thread.sleep(1000); // Wait 1 second for content to update
				}
			}
			catch (TimeoutError e) {
				log.warn("Page load state wait timeout, continuing anyway: {}", e.getMessage());
			}
			catch (PlaywrightException e) {
				log.warn("Playwright error waiting for load state, continuing anyway: {}", e.getMessage());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for page load");
			}
			catch (Exception loadException) {
				log.warn("Unexpected error waiting for load state, continuing anyway: {}", loadException.getMessage());
			}

			// Get basic information with error handling
			try {
				String currentUrl = page.url();
				String title = page.title();
				state.put("url", currentUrl != null ? currentUrl : "unknown");
				state.put("title", title != null ? title : "unknown");
			}
			catch (PlaywrightException e) {
				log.warn("Failed to get page URL/title: {}", e.getMessage());
				state.put("url", "error: " + e.getMessage());
				state.put("title", "error: " + e.getMessage());
			}
			catch (Exception e) {
				log.warn("Unexpected error getting page URL/title: {}", e.getMessage());
				state.put("url", "error: " + e.getMessage());
				state.put("title", "error: " + e.getMessage());
			}

			// Get tab information with error handling
			try {
				List<Map<String, Object>> tabs = getTabsInfo(page);
				state.put("tabs", tabs);
			}
			catch (PlaywrightException e) {
				log.warn("Failed to get tabs info: {}", e.getMessage());
				state.put("tabs", List.of(Map.of("error", "Failed to get tabs: " + e.getMessage())));
			}
			catch (Exception e) {
				log.warn("Unexpected error getting tabs info: {}", e.getMessage());
				state.put("tabs", List.of(Map.of("error", "Failed to get tabs: " + e.getMessage())));
			}

			// Wait a bit more before generating ARIA snapshot to ensure all dynamic
			// content
			// (like search results) is fully rendered
			try {
				Thread.sleep(500); // Additional wait for content rendering
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for content rendering");
			}

			// Generate ARIA snapshot using the new AriaSnapshot utility with error
			// handling
			// Note: AriaSnapshot now returns error messages instead of throwing
			// exceptions
			// for timeouts, so the flow continues normally
			try {
				AriaSnapshotOptions snapshotOptions = new AriaSnapshotOptions().setSelector("body")
					.setTimeout(getBrowserTimeout() * 1000); // Convert to milliseconds

				// Use compressUrl based on configuration
				Boolean enableShortUrl = getLynxeProperties().getEnableShortUrl();
				boolean compressUrl = enableShortUrl != null ? enableShortUrl : true; // Default
				// to
				// true
				String snapshot = AriaElementHelper.parsePageAndAssignRefs(page, snapshotOptions, compressUrl,
						shortUrlService, rootPlanId);
				if (snapshot != null && !snapshot.trim().isEmpty()) {
					// Snapshot may contain error message if timeout occurred, which is
					// fine
					state.put("interactive_elements", snapshot);
				}
				else {
					state.put("interactive_elements", "No interactive elements found or snapshot is empty");
				}
			}
			catch (PlaywrightException e) {
				log.warn("Playwright error getting ARIA snapshot: {}", e.getMessage());
				state.put("interactive_elements", "Error getting interactive elements: " + e.getMessage()
						+ ". You can continue with available page information (URL, title, tabs).");
			}
			catch (Exception e) {
				log.warn("Unexpected error getting ARIA snapshot: {}", e.getMessage());
				state.put("interactive_elements", "Error getting interactive elements: " + e.getMessage()
						+ ". You can continue with available page information (URL, title, tabs).");
			}

			return state;

		}
		catch (Exception e) {
			log.error("Failed to get browser state: {}", e.getMessage(), e);
			state.put("error", "Failed to get browser state: " + e.getMessage());
			return state;
		}
	}

	/**
	 * Get the current browser state as a string
	 * @param planId the plan ID
	 * @param rootPlanId the root plan ID
	 * @return String representation of the current browser state
	 */
	@SuppressWarnings("unchecked")
	public String getCurrentToolStateString(String planId, String rootPlanId) {
		try {
			DriverWrapper driver = getDriver(planId);
			Map<String, Object> state = getCurrentState(driver.getCurrentPage(), rootPlanId);
			// Build URL and title information
			String urlInfo = String.format("\n   URL: %s\n   Title: %s", state.get("url"), state.get("title"));

			// Build tab information

			List<Map<String, Object>> tabs = (List<Map<String, Object>>) state.get("tabs");
			String tabsInfo = (tabs != null) ? String.format("\n   %d tab(s) available", tabs.size()) : "";
			if (tabs != null) {
				for (int i = 0; i < tabs.size(); i++) {
					Map<String, Object> tab = tabs.get(i);
					String tabUrl = (String) tab.get("url");
					String tabTitle = (String) tab.get("title");
					tabsInfo += String.format("\n   [%d] %s: %s", i, tabTitle, tabUrl);
				}
			}
			// Get scroll information
			Object scrollInfoObj = state.get("scroll_info");
			String contentAbove = "";
			String contentBelow = "";
			if (scrollInfoObj instanceof Map<?, ?> scrollInfoMap) {

				Map<String, Object> scrollInfo = (Map<String, Object>) scrollInfoMap;
				Object pixelsAboveObj = scrollInfo.get("pixels_above");
				Object pixelsBelowObj = scrollInfo.get("pixels_below");

				if (pixelsAboveObj instanceof Long pixelsAbove) {
					contentAbove = pixelsAbove > 0 ? String.format(" (%d pixels)", pixelsAbove) : "";
				}
				if (pixelsBelowObj instanceof Long pixelsBelow) {
					contentBelow = pixelsBelow > 0 ? String.format(" (%d pixels)", pixelsBelow) : "";
				}
			}

			// Get interactive element information
			String elementsInfo = (String) state.get("interactive_elements");

			// Build final status string
			String retString = String.format("""

					- Current URL and page title:
					%s

					- Available tabs:
					%s

					- Interactive elements and their indices:
					%s

					- Content above%s or below%s the viewport (if indicated)

					- Any action results or errors:
					%s
					""", urlInfo, tabsInfo, elementsInfo != null ? elementsInfo : "", contentAbove, contentBelow,
					state.containsKey("error") ? state.get("error") : "");

			return retString;
		}
		catch (Exception e) {
			// Handle any unexpected errors gracefully - return a valid state string
			// This ensures the flow continues even if state retrieval fails
			log.warn("Error getting browser tool state string (non-fatal): {}", e.getMessage(), e);
			return String.format("""
					Browser tool state retrieval encountered an error: %s
					You can continue with available browser information or try again.
					""", e.getMessage());
		}
	}

	/**
	 * Cleanup browser resources for a specific plan
	 * @param planId the plan ID to cleanup
	 */
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up Chrome resources for plan: {}", planId);
			this.chromeDriverService.closeDriverForPlan(planId);
		}
	}

	/**
	 * Get LynxeProperties from ChromeDriverService
	 * @return LynxeProperties instance
	 */
	public LynxeProperties getLynxeProperties() {
		return (LynxeProperties) this.chromeDriverService.getLynxeProperties();
	}

}

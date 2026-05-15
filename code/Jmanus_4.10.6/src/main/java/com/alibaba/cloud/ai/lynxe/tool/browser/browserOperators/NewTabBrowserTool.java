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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.BrowserUseCommonService;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * New tab browser tool that opens a new tab with specified URL.
 */
public class NewTabBrowserTool extends AbstractBrowserTool<NewTabBrowserTool.NewTabInput> {

	private static final Logger log = LoggerFactory.getLogger(NewTabBrowserTool.class);

	private static final String TOOL_NAME = "new-tab-browser";

	private final ToolI18nService toolI18nService;

	/**
	 * Input class for new_tab operations
	 */
	public static class NewTabInput {

		private String url;

		// Getters and setters
		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

	}

	public NewTabBrowserTool(BrowserUseCommonService browserUseTool, ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(NewTabInput input) {
		log.info("NewTabBrowserTool request: url={}", input.getUrl());
		try {
			ToolExecuteResult validation = validateDriver();
			if (validation != null) {
				return validation;
			}

			String url = input.getUrl();
			if (url == null || url.trim().isEmpty()) {
				return new ToolExecuteResult("Error: url parameter is required");
			}

			return executeActionWithRetry(() -> {
				String finalUrl = url;
				// Check if URL is a short URL
				if (ShortUrlService.isShortUrl(finalUrl)) {
					String realUrl = getShortUrlService().getRealUrlFromShortUrl(getRootPlanId(), finalUrl);
					if (realUrl == null) {
						return new ToolExecuteResult("Short URL not found in mapping: " + finalUrl);
					}
					finalUrl = realUrl;
					log.debug("Resolved short URL {} to real URL {}", url, finalUrl);
				}

				// Auto-complete the URL prefix
				if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
					finalUrl = "https://" + finalUrl;
				}

				// Get current page to access browser context
				Page currentPage = getCurrentPage();

				// Create a new page (new tab) in the same browser context
				Page newPage = currentPage.context().newPage();

				// Navigate the new page to the specified URL
				newPage.navigate(finalUrl);

				// Set the new page as the current page in DriverWrapper
				getDriverWrapper().setCurrentPage(newPage);

				return new ToolExecuteResult("Opened new tab with URL " + finalUrl);
			}, "new_tab");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing new_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser new_tab timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing new_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser new_tab failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing new_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser new_tab failed: " + e.getMessage());
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("new-tab-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("new-tab-browser");
	}

	@Override
	public Class<NewTabInput> getInputType() {
		return NewTabInput.class;
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

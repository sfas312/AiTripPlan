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
import com.microsoft.playwright.Page.WaitForLoadStateOptions;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;

/**
 * Navigate browser tool that visits a specific URL.
 */
public class NavigateBrowserTool extends AbstractBrowserTool<NavigateBrowserTool.NavigateInput> {

	private static final Logger log = LoggerFactory.getLogger(NavigateBrowserTool.class);

	private static final String TOOL_NAME = "navigate-browser";

	private final ToolI18nService toolI18nService;

	/**
	 * Input class for navigate operations
	 */
	public static class NavigateInput {

		private String url;

		// Getters and setters
		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

	}

	public NavigateBrowserTool(BrowserUseCommonService browserUseTool, ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(NavigateInput input) {
		log.info("NavigateBrowserTool request: url={}", input.getUrl());
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
				Integer timeoutMs = getBrowserTimeoutMs();
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
				Page page = getCurrentPage(); // Get the Playwright Page instance
				page.navigate(finalUrl, new Page.NavigateOptions().setTimeout(timeoutMs));

				// Before calling page.content(), ensure the page is fully loaded
				page.waitForLoadState(LoadState.DOMCONTENTLOADED, new WaitForLoadStateOptions().setTimeout(timeoutMs));

				// Save storage state after navigation to persist cookies, localStorage,
				// etc.
				// Following Playwright best practices: use storage state instead of
				// manual cookie
				// management
				try {
					getBrowserUseTool().getDriver(getCurrentPlanId()).saveStorageState();
				}
				catch (Exception e) {
					// Log but don't fail the navigation if storage state saving fails
					log.debug("Failed to save storage state after navigation: {}", e.getMessage());
				}

				return new ToolExecuteResult("successfully navigated to " + finalUrl);
			}, "navigate");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing navigate: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser navigate timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing navigate: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser navigate failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing navigate: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser navigate failed: " + e.getMessage());
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("navigate-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("navigate-browser");
	}

	@Override
	public Class<NavigateInput> getInputType() {
		return NavigateInput.class;
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

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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.BrowserUseCommonService;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * Switch tab browser tool that switches to a specific tab.
 */
public class SwitchTabBrowserTool extends AbstractBrowserTool<SwitchTabBrowserTool.SwitchTabInput> {

	private static final Logger log = LoggerFactory.getLogger(SwitchTabBrowserTool.class);

	private static final String TOOL_NAME = "switch-tab-browser";

	private final ToolI18nService toolI18nService;

	/**
	 * Input class for switch_tab operations
	 */
	public static class SwitchTabInput {

		@JsonProperty("tab_id")
		private Integer tabId;

		// Getters and setters
		public Integer getTabId() {
			return tabId;
		}

		public void setTabId(Integer tabId) {
			this.tabId = tabId;
		}

	}

	public SwitchTabBrowserTool(BrowserUseCommonService browserUseTool, ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(SwitchTabInput input) {
		log.info("SwitchTabBrowserTool request: tab_id={}", input.getTabId());
		try {
			ToolExecuteResult validation = validateDriver();
			if (validation != null) {
				return validation;
			}

			Integer tabId = input.getTabId();
			if (tabId == null || tabId < 0) {
				return new ToolExecuteResult("Error: tab_id parameter is required and must be non-negative");
			}

			return executeActionWithRetry(() -> {
				Page page = getCurrentPage(); // Get Playwright Page instance
				List<Page> pages = page.context().pages();

				// Check if tabId is within valid range
				if (tabId >= pages.size()) {
					return new ToolExecuteResult(
							"Tab ID " + tabId + " is out of range. Available tabs: 0 to " + (pages.size() - 1));
				}

				Page targetPage = pages.get(tabId); // Switch to specified tab
				if (targetPage == null) {
					return new ToolExecuteResult("Tab ID " + tabId + " does not exist");
				}

				// Check if target page is closed
				if (targetPage.isClosed()) {
					return new ToolExecuteResult("Tab ID " + tabId + " is closed");
				}

				// Bring the target page to front to actually activate the tab
				targetPage.bringToFront();

				// Update the current page in DriverWrapper
				getDriverWrapper().setCurrentPage(targetPage);

				return new ToolExecuteResult("Successfully switched to tab " + tabId);
			}, "switch_tab");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing switch_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser switch_tab timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing switch_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser switch_tab failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing switch_tab: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser switch_tab failed: " + e.getMessage());
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("switch-tab-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("switch-tab-browser");
	}

	@Override
	public Class<SwitchTabInput> getInputType() {
		return SwitchTabInput.class;
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

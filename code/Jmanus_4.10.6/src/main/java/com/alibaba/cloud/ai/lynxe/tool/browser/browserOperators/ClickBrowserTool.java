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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.BoundingBox;

/**
 * Click browser tool that clicks an element by index.
 */
public class ClickBrowserTool extends AbstractBrowserTool<ClickBrowserTool.ClickInput> {

	private static final Logger log = LoggerFactory.getLogger(ClickBrowserTool.class);

	private static final String TOOL_NAME = "click-browser";

	private final ToolI18nService toolI18nService;

	/**
	 * Input class for click operations
	 */
	public static class ClickInput {

		private Integer index;

		// Getters and setters
		public Integer getIndex() {
			return index;
		}

		public void setIndex(Integer index) {
			this.index = index;
		}

	}

	public ClickBrowserTool(BrowserUseCommonService browserUseTool, ToolI18nService toolI18nService) {
		super(browserUseTool);
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ClickInput input) {
		log.info("ClickBrowserTool request: index={}", input.getIndex());
		try {
			ToolExecuteResult validation = validateDriver();
			if (validation != null) {
				return validation;
			}

			Integer index = input.getIndex();
			if (index == null) {
				return new ToolExecuteResult("Error: index parameter is required");
			}

			// Check if element exists
			if (!elementExistsByIdx(index)) {
				return new ToolExecuteResult("Element with index " + index + " not found in ARIA snapshot");
			}

			Page page = getCurrentPage();
			Locator locator = getLocatorByIdx(index);
			if (locator == null) {
				return new ToolExecuteResult("Failed to create locator for element with index " + index);
			}

			return executeActionWithRetry(() -> {
				String clickResultMessage = clickAndSwitchToNewTabIfOpened(page, () -> {
					// Primary method: Use mouse simulation click
					log.debug("Attempting primary method: mouse simulation click for element at index {}", index);
					clickWithMouseSimulation(page, locator, index);
					log.info("Successfully clicked element at index {} using mouse simulation (primary method)", index);
				});
				return new ToolExecuteResult(
						"Successfully clicked element at index " + index + " " + clickResultMessage);
			}, "click");
		}
		catch (TimeoutError e) {
			log.error("Timeout error executing click: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser click timed out: " + e.getMessage());
		}
		catch (PlaywrightException e) {
			log.error("Playwright error executing click: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser click failed due to Playwright error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Unexpected error executing click: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser click failed: " + e.getMessage());
		}
	}

	/**
	 * Primary method: Simulate mouse movement to element center and click This method
	 * moves the mouse to the center of the element and performs a click
	 * @param page The Playwright Page instance
	 * @param locator The Locator for the element
	 * @param index The element index for logging
	 * @throws RuntimeException if the mouse simulation fails
	 */
	private void clickWithMouseSimulation(Page page, Locator locator, Integer index) {
		try {
			// First, try to scroll element into view to ensure it's accessible
			try {
				locator.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3000));
				log.debug("Element scrolled into view before getting bounding box");
			}
			catch (TimeoutError scrollError) {
				log.warn("Failed to scroll element into view before getting bounding box: {}",
						scrollError.getMessage());
			}

			// Wait for element to be visible
			try {
				locator.waitFor(new Locator.WaitForOptions().setTimeout(3000)
					.setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
			}
			catch (TimeoutError waitError) {
				log.warn("Element may not be visible: {}", waitError.getMessage());
			}

			// Get element bounding box to calculate center coordinates
			BoundingBox box = locator.boundingBox(new Locator.BoundingBoxOptions().setTimeout(5000));
			if (box == null) {
				// Check if element exists and is visible for better error message
				String visibilityInfo = "unknown";
				try {
					boolean isVisible = locator.isVisible();
					visibilityInfo = isVisible ? "visible but no bounding box" : "not visible";
				}
				catch (Exception e) {
					log.debug("Could not check element visibility: {}", e.getMessage());
					visibilityInfo = "check failed";
				}

				String errorMessage = String.format(
						"Element not found or not visible (index: %d, visibility: %s). Please check the page: the element may not be in the current viewport, may be obscured by other elements, or the page may have changed.",
						index, visibilityInfo);
				log.error(errorMessage);
				throw new RuntimeException(errorMessage);
			}

			// Calculate center point of the element
			double centerX = box.x + box.width / 2.0;
			double centerY = box.y + box.height / 2.0;

			log.debug("Element at index {} bounding box: x={}, y={}, width={}, height={}", index, box.x, box.y,
					box.width, box.height);
			log.debug("Calculated center point: ({}, {})", centerX, centerY);

			// Recalculate bounding box to ensure we have the latest position
			BoundingBox updatedBox = locator.boundingBox(new Locator.BoundingBoxOptions().setTimeout(3000));
			if (updatedBox != null) {
				centerX = updatedBox.x + updatedBox.width / 2.0;
				centerY = updatedBox.y + updatedBox.height / 2.0;
				log.debug("Updated center point: ({}, {})", centerX, centerY);
			}

			// Move mouse to the center of the element
			page.mouse().move(centerX, centerY);
			log.debug("Mouse moved to position ({}, {})", centerX, centerY);

			// Small delay to ensure mouse movement is registered
			Thread.sleep(100);

			// Click at the center position
			page.mouse().click(centerX, centerY);
			log.info("Mouse clicked at position ({}, {}) for element at index {}", centerX, centerY, index);

			// Add small delay to ensure the action is processed
			Thread.sleep(500);

		}
		catch (TimeoutError e) {
			String errorMessage = String.format(
					"Timeout getting element bounding box (index: %d). The element may not be fully loaded or may not be on the current page. Please check the page state.",
					index);
			log.error("Timeout getting bounding box for mouse simulation on element with idx {}: {}", index,
					e.getMessage());
			throw new RuntimeException(errorMessage, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Interrupted during mouse simulation for element with idx {}: {}", index, e.getMessage());
			throw new RuntimeException("Interrupted during mouse simulation", e);
		}
		catch (Exception e) {
			log.error("Error during mouse simulation click on element with idx {}: {}", index, e.getMessage());
			throw new RuntimeException("Error during mouse simulation click: " + e.getMessage(), e);
		}
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("click-browser");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("click-browser");
	}

	@Override
	public Class<ClickInput> getInputType() {
		return ClickInput.class;
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

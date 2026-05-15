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
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.BrowserUseCommonService;
import com.alibaba.cloud.ai.lynxe.tool.browser.service.DriverWrapper;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

/**
 * Abstract base class for browser tools that provides shared functionality.
 */
public abstract class AbstractBrowserTool<T> extends AbstractBaseTool<T> {

	protected static final Logger log = LoggerFactory.getLogger(AbstractBrowserTool.class);

	protected final BrowserUseCommonService browserUseTool;

	public AbstractBrowserTool(BrowserUseCommonService browserUseTool) {
		this.browserUseTool = browserUseTool;
	}

	/**
	 * Execute action with retry mechanism for better reliability
	 */
	protected ToolExecuteResult executeActionWithRetry(ActionExecutor executor, String actionName) {
		int maxRetries = 2;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				return executor.execute();
			}
			catch (TimeoutError e) {
				if (attempt == maxRetries) {
					log.error("Action '{}' timed out after {} attempts: {}", actionName, maxRetries, e.getMessage());
					throw e;
				}
				log.warn("Action '{}' timed out on attempt {}, retrying: {}", actionName, attempt, e.getMessage());
			}
			catch (PlaywrightException e) {
				// Some Playwright exceptions are not worth retrying
				if (e.getMessage().contains("Target page, context or browser has been closed")
						|| e.getMessage().contains("Browser has been closed")
						|| e.getMessage().contains("Context has been closed")) {
					log.error("Action '{}' failed due to closed browser/context: {}", actionName, e.getMessage());
					throw e;
				}

				if (attempt == maxRetries) {
					log.error("Action '{}' failed after {} attempts: {}", actionName, maxRetries, e.getMessage());
					throw e;
				}
				log.warn("Action '{}' failed on attempt {}, retrying: {}", actionName, attempt, e.getMessage());
			}
			catch (RuntimeException e) {
				// For runtime exceptions, don't retry
				log.error("Action '{}' failed with non-retryable error: {}", actionName, e.getMessage());
				throw e;
			}
			catch (Exception e) {
				// For checked exceptions, wrap and don't retry
				log.error("Action '{}' failed with non-retryable error: {}", actionName, e.getMessage());
				throw new RuntimeException("Action failed: " + actionName, e);
			}

			// Wait before retry
			try {
				Thread.sleep(retryDelay);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted during retry delay for action: " + actionName, ie);
			}
		}

		// Should never reach here
		throw new RuntimeException("Unexpected end of retry loop for action: " + actionName);
	}

	/**
	 * Validate driver availability before executing any action
	 */
	protected ToolExecuteResult validateDriver() {
		try {
			com.alibaba.cloud.ai.lynxe.tool.browser.service.DriverWrapper driver = browserUseTool
				.getDriver(getCurrentPlanId());
			if (driver == null) {
				return new ToolExecuteResult("Browser driver is not available");
			}

			// Check if browser is still connected
			if (driver.getBrowser() == null || !driver.getBrowser().isConnected()) {
				return new ToolExecuteResult("Browser is not connected. Please try again or restart the browser.");
			}

			// Check if current page is valid
			com.microsoft.playwright.Page currentPage = driver.getCurrentPage();
			if (currentPage == null || currentPage.isClosed()) {
				return new ToolExecuteResult("Current page is not available. Please navigate to a page first.");
			}
			return null; // Validation passed
		}
		catch (Exception e) {
			log.error("Driver validation failed: {}", e.getMessage(), e);
			return new ToolExecuteResult("Browser driver validation failed: " + e.getMessage());
		}
	}

	/**
	 * Get browser use tool instance
	 * @return BrowserUseCommonService instance
	 */
	protected BrowserUseCommonService getBrowserUseTool() {
		return browserUseTool;
	}

	/**
	 * Get browser operation timeout configuration
	 * @return Timeout in milliseconds, returns default value of 30 seconds if not
	 * configured
	 */
	protected Integer getBrowserTimeoutMs() {
		Integer timeout = browserUseTool.getLynxeProperties().getBrowserRequestTimeout();
		return (timeout != null ? timeout : 30) * 1000; // Convert to milliseconds
	}

	/**
	 * Get browser operation timeout configuration
	 * @return Timeout in seconds, returns default value of 30 seconds if not configured
	 */
	protected Integer getBrowserTimeoutSec() {
		Integer timeout = browserUseTool.getLynxeProperties().getBrowserRequestTimeout();
		return timeout != null ? timeout : 30; // Default timeout is 30 seconds
	}

	/**
	 * Get reasonable timeout for element operations (capped at 10 seconds) This prevents
	 * long waits when elements are not found or not ready
	 * @return Timeout in milliseconds, capped at 10 seconds
	 */
	protected Integer getElementTimeoutMs() {
		return Math.min(getBrowserTimeoutMs(), 10000); // Max 10 seconds for element
														// operations
	}

	/**
	 * Simulate human behavior
	 * @param element Playwright ElementHandle instance
	 */
	protected void simulateHumanBehavior(ElementHandle element) {
		try {
			// Add random delay
			Thread.sleep(new Random().nextInt(500) + 200);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Get DriverWrapper instance
	 * @return DriverWrapper
	 */
	protected DriverWrapper getDriverWrapper() {
		return browserUseTool.getDriver(getCurrentPlanId());
	}

	/**
	 * Get ShortUrlService instance
	 * @return ShortUrlService
	 */
	protected com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService getShortUrlService() {
		return browserUseTool.getShortUrlService();
	}

	/**
	 * Get current page Page instance
	 * @return Current Playwright Page instance
	 */
	protected Page getCurrentPage() {
		DriverWrapper driverWrapper = getDriverWrapper();
		return driverWrapper.getCurrentPage();
	}

	/**
	 * Get locator for element by idx (from ARIA snapshot) Converts idx to aria-id-num
	 * format and uses data-aria-id attribute to locate the element
	 * @param idx Element idx (from ARIA snapshot)
	 * @return Locator for the element, or null if not found
	 */
	protected Locator getLocatorByIdx(int idx) {
		Page page = getCurrentPage();
		if (page == null) {
			return null;
		}

		try {
			// Convert idx to aria-id-num format
			String dataAriaId = "aria-id-" + idx;

			// Escape single quotes in the value for CSS selector safety
			String escapedDataAriaId = dataAriaId.replace("'", "\\'");

			// Use data-aria-id attribute to locate the element
			Locator dataAriaIdLocator = page.locator("[aria-label='" + escapedDataAriaId + "']");

			return dataAriaIdLocator;
		}
		catch (Exception e) {
			log.warn("Failed to get locator by idx {}: {}", idx, e.getMessage());
			return null;
		}
	}

	/**
	 * Check if element exists by idx
	 * @param idx Element idx (from ARIA snapshot)
	 * @return true if element exists, false otherwise
	 */
	protected boolean elementExistsByIdx(int idx) {
		return getLocatorByIdx(idx) != null;
	}

	/**
	 * Handle click action and detect if a new tab was opened
	 * @param pageToClickOn The page where the click happens
	 * @param clickLambda The lambda that performs the click
	 * @return Message describing the result of the click action
	 */
	protected String clickAndSwitchToNewTabIfOpened(Page pageToClickOn, Runnable clickLambda) {
		Page newPageFromPopup = null;
		String originalPageUrl = pageToClickOn.url();
		BrowserContext context = pageToClickOn.context();
		List<Page> pagesBeforeClick = context.pages();
		Set<String> urlsBeforeClick = pagesBeforeClick.stream().map(Page::url).collect(Collectors.toSet());

		try {
			Integer timeout = getBrowserTimeoutMs();
			// Use the minimum of configured timeout and 2 seconds for popup detection
			int popupTimeout = Math.min(timeout, 2000);
			Page.WaitForPopupOptions popupOptions = new Page.WaitForPopupOptions().setTimeout(popupTimeout);

			log.debug("Using popup timeout: {}ms, browser timeout: {}ms", popupTimeout, timeout);

			newPageFromPopup = pageToClickOn.waitForPopup(popupOptions, clickLambda);

			if (newPageFromPopup != null) {
				log.info("waitForPopup detected new page: {}", newPageFromPopup.url());
				if (getDriverWrapper().getCurrentPage() != newPageFromPopup) {
					getDriverWrapper().setCurrentPage(newPageFromPopup);
				}
				return "successfully, and opened in new tab: " + newPageFromPopup.url();
			}

			// Fallback if newPageFromPopup is null but no exception (unlikely for
			// waitForPopup)
			if (!pageToClickOn.isClosed() && !pageToClickOn.url().equals(originalPageUrl)) {
				log.info("Page navigated in the same tab (fallback check): {}", pageToClickOn.url());
				return "and navigated in the same tab to: " + pageToClickOn.url();
			}
			return "successfully.";

		}
		catch (TimeoutError e) {
			log.warn(
					"No popup detected by waitForPopup within timeout. Click action was performed. Checking page states...");

			List<Page> pagesAfterTimeout = context.pages();
			List<Page> newPagesByDiff = pagesAfterTimeout.stream()
				.filter(p -> !urlsBeforeClick.contains(p.url()))
				.collect(Collectors.toList());

			if (!newPagesByDiff.isEmpty()) {
				Page newlyFoundPage = newPagesByDiff.get(0);
				log.info("New tab found by diffing URLs after waitForPopup timeout: {}", newlyFoundPage.url());
				getDriverWrapper().setCurrentPage(newlyFoundPage);
				return "successfully, and opened in new tab: " + newlyFoundPage.url();
			}

			if (!pageToClickOn.isClosed() && !pageToClickOn.url().equals(originalPageUrl)) {
				if (getDriverWrapper().getCurrentPage() != pageToClickOn) {
					getDriverWrapper().setCurrentPage(pageToClickOn);
				}
				log.info("Page navigated in the same tab after timeout: {}", pageToClickOn.url());
				return "and navigated in the same tab to: " + pageToClickOn.url();
			}

			Page currentPageInWrapper = getDriverWrapper().getCurrentPage();
			if (pageToClickOn.isClosed() && currentPageInWrapper != null && !currentPageInWrapper.isClosed()
					&& !urlsBeforeClick.contains(currentPageInWrapper.url())) {
				log.info("Original page closed, current page is now: {}", currentPageInWrapper.url());
				return "and current page changed to: " + currentPageInWrapper.url();
			}
			log.info("No new tab or significant navigation detected after timeout.");
			return "successfully.";
		}
		catch (Exception e) {
			log.error("Exception during click or popup handling: {}", e.getMessage(), e);

			List<Page> pagesAfterError = context.pages();
			List<Page> newPagesByDiffAfterError = pagesAfterError.stream()
				.filter(p -> !urlsBeforeClick.contains(p.url()))
				.collect(Collectors.toList());
			if (!newPagesByDiffAfterError.isEmpty()) {
				Page newlyFoundPage = newPagesByDiffAfterError.get(0);
				log.info("New tab found by diffing URLs after an error: {}", newlyFoundPage.url());
				getDriverWrapper().setCurrentPage(newlyFoundPage);
				return "successfully, and opened in new tab: " + newlyFoundPage.url();
			}
			return "successfully.";
		}
	}

	/**
	 * Get LynxeProperties from BrowserUseCommonService
	 * @return LynxeProperties instance
	 */
	protected com.alibaba.cloud.ai.lynxe.config.LynxeProperties getLynxeProperties() {
		return browserUseTool.getLynxeProperties();
	}

	/**
	 * Functional interface for action execution
	 */
	@FunctionalInterface
	protected interface ActionExecutor {

		ToolExecuteResult execute() throws Exception;

	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up browser resources for plan: {}", planId);
			browserUseTool.cleanup(planId);
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

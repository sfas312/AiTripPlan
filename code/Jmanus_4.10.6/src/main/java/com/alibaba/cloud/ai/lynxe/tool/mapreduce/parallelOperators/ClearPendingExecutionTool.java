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
package com.alibaba.cloud.ai.lynxe.tool.mapreduce.parallelOperators;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.FunctionRegistryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Clear pending execution tool that clears all pending functions (functions with no
 * result yet).
 */
public class ClearPendingExecutionTool extends AbstractBaseTool<ClearPendingExecutionTool.ClearPendingInput>
		implements AsyncToolCallBiFunctionDef<ClearPendingExecutionTool.ClearPendingInput> {

	private static final Logger logger = LoggerFactory.getLogger(ClearPendingExecutionTool.class);

	private static final String TOOL_NAME = "clear-pending-execution";

	/**
	 * Input class for clear pending (no parameters needed)
	 */
	public static class ClearPendingInput {

		// No parameters needed

	}

	private final ObjectMapper objectMapper;

	private final FunctionRegistryService functionRegistryService;

	private final ToolI18nService toolI18nService;

	public ClearPendingExecutionTool(ObjectMapper objectMapper, FunctionRegistryService functionRegistryService,
			ToolI18nService toolI18nService) {
		this.objectMapper = objectMapper;
		this.functionRegistryService = functionRegistryService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult apply(ClearPendingInput input, ToolContext toolContext) {
		return applyAsync(input, toolContext).join();
	}

	@Override
	public CompletableFuture<ToolExecuteResult> applyAsync(ClearPendingInput input, ToolContext toolContext) {
		try {
			String planId = this.currentPlanId;
			List<FunctionRegistryService.FunctionRegistry> functionRegistries = functionRegistryService
				.getRegistries(planId);

			int clearedCount = 0;
			for (FunctionRegistryService.FunctionRegistry function : functionRegistries) {
				if (function.getResult() == null) {
					function.setResult(new ToolExecuteResult("Cleared"));
					clearedCount++;
				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("message", "Cleared " + clearedCount + " pending functions");
			result.put("count", clearedCount);
			try {
				return CompletableFuture
					.completedFuture(new ToolExecuteResult(objectMapper.writeValueAsString(result)));
			}
			catch (JsonProcessingException e) {
				logger.error("Error serializing result: {}", e.getMessage(), e);
				return CompletableFuture
					.completedFuture(new ToolExecuteResult("Cleared " + clearedCount + " pending functions"));
			}
		}
		catch (Exception e) {
			logger.error("Error clearing pending functions: {}", e.getMessage(), e);
			return CompletableFuture
				.completedFuture(new ToolExecuteResult("Error clearing pending functions: " + e.getMessage()));
		}
	}

	@Override
	public ToolExecuteResult run(ClearPendingInput input) {
		throw new UnsupportedOperationException(
				"ClearPendingExecutionTool must be called using apply() method with ToolContext, not run()");
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
		return toolI18nService.getDescription("clear-pending-execution");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("clear-pending-execution");
	}

	@Override
	public Class<ClearPendingInput> getInputType() {
		return ClearPendingInput.class;
	}

	@Override
	public void cleanup(String planId) {
		// Cleanup is handled by FunctionRegistryService
	}

	@Override
	public String getServiceGroup() {
		return "parallel";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

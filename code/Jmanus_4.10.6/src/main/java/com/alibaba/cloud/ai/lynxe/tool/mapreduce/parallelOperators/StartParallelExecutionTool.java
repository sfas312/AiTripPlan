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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.FunctionRegistryService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.ParallelExecutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Start parallel execution tool that executes all registered functions in parallel. Uses
 * asynchronous non-blocking execution to prevent thread pool starvation.
 */
public class StartParallelExecutionTool extends AbstractBaseTool<StartParallelExecutionTool.StartInput>
		implements AsyncToolCallBiFunctionDef<StartParallelExecutionTool.StartInput> {

	private static final Logger logger = LoggerFactory.getLogger(StartParallelExecutionTool.class);

	private static final String TOOL_NAME = "start-parallel";

	/**
	 * Input class for start execution (no parameters needed)
	 */
	public static class StartInput {

		// No parameters needed

	}

	private final ObjectMapper objectMapper;

	private final Map<String, ToolCallBackContext> toolCallbackMap;

	private final FunctionRegistryService functionRegistryService;

	private final ParallelExecutionService parallelExecutionService;

	private final ToolI18nService toolI18nService;

	public StartParallelExecutionTool(ObjectMapper objectMapper, Map<String, ToolCallBackContext> toolCallbackMap,
			FunctionRegistryService functionRegistryService, ParallelExecutionService parallelExecutionService,
			ToolI18nService toolI18nService) {
		this.objectMapper = objectMapper;
		this.toolCallbackMap = toolCallbackMap;
		this.functionRegistryService = functionRegistryService;
		this.parallelExecutionService = parallelExecutionService;
		this.toolI18nService = toolI18nService;
	}

	/**
	 * Set the tool callback map (used to look up actual tool implementations)
	 */
	public void setToolCallbackMap(Map<String, ToolCallBackContext> toolCallbackMap) {
		this.toolCallbackMap.putAll(toolCallbackMap);
	}

	@Override
	public ToolExecuteResult apply(StartInput input, ToolContext toolContext) {
		return applyAsync(input, toolContext).join();
	}

	@Override
	public CompletableFuture<ToolExecuteResult> applyAsync(StartInput input, ToolContext parentToolContext) {
		try {
			String planId = this.currentPlanId;
			List<FunctionRegistryService.FunctionRegistry> functionRegistries = functionRegistryService
				.getRegistries(planId);

			if (functionRegistries.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No functions registered"));
			}

			// Collect pending functions and create execution requests
			List<FunctionRegistryService.FunctionRegistry> pendingFunctions = new ArrayList<>();
			List<ParallelExecutionService.ParallelExecutionRequest> executions = new ArrayList<>();

			for (FunctionRegistryService.FunctionRegistry function : functionRegistries) {
				if (function.getResult() != null) {
					continue; // Skip already executed functions
				}

				String toolName = function.getToolName();
				ToolCallBackContext toolContext = parallelExecutionService.lookupToolContext(toolName, toolCallbackMap);

				if (toolContext == null) {
					logger.warn("Tool not found in callback map: {}", toolName);
					function.setResult(new ToolExecuteResult("Tool not found: " + toolName));
					continue;
				}

				pendingFunctions.add(function);
				executions.add(new ParallelExecutionService.ParallelExecutionRequest(toolName, function.getInput()));
			}

			if (executions.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No pending functions to execute"));
			}

			// Use ParallelExecutionService to execute all tools in parallel
			return parallelExecutionService.executeToolsInParallel(executions, toolCallbackMap, parentToolContext)
				.thenApply(results -> {
					// Map results back to FunctionRegistry objects
					for (int i = 0; i < pendingFunctions.size() && i < results.size(); i++) {
						FunctionRegistryService.FunctionRegistry function = pendingFunctions.get(i);
						Map<String, Object> result = results.get(i);

						String status = (String) result.get("status");
						if ("SUCCESS".equals(status)) {
							Object outputObj = result.get("output");
							String output = outputObj != null ? outputObj.toString() : "No output";
							if (output != null) {
								output = output.replace("\\\"", "\"").replace("\\\\", "\\");
							}
							function.setResult(new ToolExecuteResult(output));
						}
						else {
							Object errorObj = result.get("error");
							String error = errorObj != null ? errorObj.toString() : "Unknown error";
							function.setResult(new ToolExecuteResult("Error: " + error));
						}
					}

					// Build result with function IDs
					List<Map<String, Object>> resultList = new ArrayList<>();
					for (FunctionRegistryService.FunctionRegistry function : functionRegistries) {
						if (function.getResult() != null) {
							Map<String, Object> item = new HashMap<>();
							item.put("id", function.getId());
							item.put("status", "COMPLETED");
							String output = null;
							try {
								output = function.getResult().getOutput();
								if (output != null) {
									output = output.replace("\\\"", "\"").replace("\\\\", "\\");
								}
							}
							catch (Exception ignore) {
							}
							if (output == null) {
								output = "No output";
							}
							item.put("output", output);
							resultList.add(item);
						}
					}

					Map<String, Object> finalResult = new HashMap<>();
					finalResult.put("results", resultList);
					finalResult.put("message", "Successfully executed " + executions.size() + " functions");
					try {
						return new ToolExecuteResult(objectMapper.writeValueAsString(finalResult));
					}
					catch (JsonProcessingException e) {
						logger.error("Error serializing result: {}", e.getMessage(), e);
						return new ToolExecuteResult("Successfully executed " + executions.size() + " functions");
					}
				})
				.exceptionally(ex -> {
					logger.error("Error in async execution: {}", ex.getMessage(), ex);
					return new ToolExecuteResult("Error starting execution: " + ex.getMessage());
				});
		}
		catch (Exception e) {
			logger.error("Error starting async execution: {}", e.getMessage(), e);
			return CompletableFuture
				.completedFuture(new ToolExecuteResult("Error starting execution: " + e.getMessage()));
		}
	}

	@Override
	public ToolExecuteResult run(StartInput input) {
		throw new UnsupportedOperationException(
				"StartParallelExecutionTool must be called using apply() method with ToolContext, not run()");
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
		return toolI18nService.getDescription("start-parallel");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("start-parallel");
	}

	@Override
	public Class<StartInput> getInputType() {
		return StartInput.class;
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

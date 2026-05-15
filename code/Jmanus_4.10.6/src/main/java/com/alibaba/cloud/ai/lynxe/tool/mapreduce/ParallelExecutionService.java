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
package com.alibaba.cloud.ai.lynxe.tool.mapreduce;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.runtime.executor.ExecutorPoolProvider;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.TerminateTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Common service for parallel execution of tools. Handles the execution logic shared
 * between parallel execution operators and FileBasedParallelExecutionTool.
 */
@Service
public class ParallelExecutionService {

	private static final Logger logger = LoggerFactory.getLogger(ParallelExecutionService.class);

	private final ObjectMapper objectMapper;

	private final PlanIdDispatcher planIdDispatcher;

	private final ExecutorPoolProvider executorPoolProvider;

	private final ServiceGroupIndexService serviceGroupIndexService;

	public ParallelExecutionService(ObjectMapper objectMapper, PlanIdDispatcher planIdDispatcher,
			ExecutorPoolProvider executorPoolProvider, ServiceGroupIndexService serviceGroupIndexService) {
		this.objectMapper = objectMapper;
		this.planIdDispatcher = planIdDispatcher;
		this.executorPoolProvider = executorPoolProvider;
		this.serviceGroupIndexService = serviceGroupIndexService;
	}

	/**
	 * Look up tool context using qualified key conversion This method handles the
	 * conversion from raw tool name to qualified key format (serviceGroup-toolName) based
	 * on serviceGroup, and provides fallback to original toolName if conversion fails.
	 * Supports both serviceGroup.toolName (dot format) and serviceGroup-toolName (hyphen
	 * format).
	 * @param toolName The raw tool name to look up (can be in serviceGroup-toolName
	 * format or serviceGroup.toolName format)
	 * @param toolCallbackMap Map of tool callbacks
	 * @return ToolCallBackContext if found, null otherwise
	 */
	public ToolCallBackContext lookupToolContext(String toolName, Map<String, ToolCallBackContext> toolCallbackMap) {
		if (toolName == null || toolName.trim().isEmpty()) {
			return null;
		}

		// First, try direct lookup in case tool name is already in serviceGroup-toolName
		// format
		ToolCallBackContext toolContext = toolCallbackMap.get(toolName);
		if (toolContext != null) {
			logger.debug("Found tool using direct lookup with key '{}'", toolName);
			return toolContext;
		}

		// If direct lookup failed, try conversion from serviceGroup.toolName to
		// serviceGroup-toolName format
		String lookupKey = toolName;
		if (serviceGroupIndexService != null) {
			try {
				String convertedKey = serviceGroupIndexService.constructFrontendToolKey(toolName);
				if (convertedKey != null && !convertedKey.equals(toolName)) {
					lookupKey = convertedKey;
					logger.debug("Converted tool key from '{}' to '{}' for lookup", toolName, lookupKey);
					// Try lookup with converted key
					toolContext = toolCallbackMap.get(lookupKey);
					if (toolContext != null) {
						return toolContext;
					}
				}
			}
			catch (Exception e) {
				logger.debug("Failed to convert tool key '{}' in lookupToolContext: {}", toolName, e.getMessage());
			}
		}

		// If still not found, try to find by unqualified tool name (backward
		// compatibility)
		// This handles cases where tool might be registered without serviceGroup prefix
		if (toolContext == null) {
			// Extract tool name part if it's in serviceGroup-toolName format
			int lastHyphenIndex = toolName.lastIndexOf('-');
			if (lastHyphenIndex > 0 && lastHyphenIndex < toolName.length() - 1) {
				String toolNamePart = toolName.substring(lastHyphenIndex + 1);
				toolContext = toolCallbackMap.get(toolNamePart);
				if (toolContext != null) {
					logger.debug("Found tool using unqualified name '{}' from qualified key '{}'", toolNamePart,
							toolName);
					return toolContext;
				}
			}
		}

		logger.debug("Tool not found for key '{}'", toolName);
		return null;
	}

	/**
	 * Execute a single tool with given parameters
	 * @param toolName Name of the tool to execute
	 * @param params Parameters for the tool
	 * @param toolCallbackMap Map of tool callbacks
	 * @param toolContext Parent tool context (for propagating toolCallId and planDepth)
	 * @param index Index for result tracking (can be null)
	 * @return CompletableFuture that completes with execution result
	 */
	public CompletableFuture<Map<String, Object>> executeTool(String toolName, Map<String, Object> params,
			Map<String, ToolCallBackContext> toolCallbackMap, ToolContext toolContext, Integer index) {
		// Use common lookup method
		ToolCallBackContext toolContextBackend = lookupToolContext(toolName, toolCallbackMap);

		if (toolContextBackend == null) {
			Map<String, Object> errorResult = new HashMap<>();
			if (index != null) {
				errorResult.put("index", index);
			}
			errorResult.put("status", "ERROR");
			errorResult.put("error", "Tool not found: " + toolName);
			return CompletableFuture.completedFuture(errorResult);
		}

		ToolCallBiFunctionDef<?> functionInstance = toolContextBackend.getFunctionInstance();

		// Get tool's expected input type and required parameters from schema
		Class<?> inputType = functionInstance.getInputType();
		List<String> requiredParamNames = getRequiredParameterNames(functionInstance);

		// Fill missing required parameters with empty string
		Map<String, Object> filledParams = fillMissingParameters(params, requiredParamNames);

		// Extract planDepth and toolCallId from context if available
		// If toolCallId is provided in context, use it to ensure consistency with
		// ActToolParam
		// Otherwise, generate a new one for proper sub-plan association
		Integer propagatedPlanDepth = null;
		String toolCallId = null;
		try {
			if (toolContext != null && toolContext.getContext() != null) {
				// Extract planDepth
				Object d = toolContext.getContext().get("planDepth");
				if (d instanceof Number) {
					propagatedPlanDepth = ((Number) d).intValue();
				}
				else if (d instanceof String) {
					propagatedPlanDepth = Integer.parseInt((String) d);
				}
				// Extract toolCallId if provided (for consistency with ActToolParam)
				Object t = toolContext.getContext().get("toolcallId");
				if (t != null) {
					toolCallId = String.valueOf(t);
				}
			}
		}
		catch (Exception ignore) {
			// ignore extraction errors
		}

		// Generate a unique tool call ID if not provided in context
		// This ensures each tool call has its own toolCallId for proper sub-plan
		// association
		if (toolCallId == null) {
			toolCallId = planIdDispatcher.generateToolCallId();
		}

		// Determine depth level
		final int depthLevel = (propagatedPlanDepth != null) ? propagatedPlanDepth : 0;

		// Check if tool supports async execution
		boolean isAsyncTool = functionInstance instanceof AsyncToolCallBiFunctionDef;

		// Convert Map to expected input type
		Object convertedInput;
		try {
			if (inputType == Map.class || Map.class.isAssignableFrom(inputType)) {
				convertedInput = filledParams;
			}
			else {
				convertedInput = objectMapper.convertValue(filledParams, inputType);
			}
		}
		catch (Exception e) {
			logger.error("Error converting input for tool {}: {}", toolName, e.getMessage(), e);
			Map<String, Object> errorResult = new HashMap<>();
			if (index != null) {
				errorResult.put("index", index);
			}
			errorResult.put("status", "ERROR");
			errorResult.put("error", "Error converting input: " + e.getMessage());
			return CompletableFuture.completedFuture(errorResult);
		}

		// Create ToolContext for this execution
		// Copy all context from parent to preserve recursive call chain and other context
		Map<String, Object> executionContextMap = new HashMap<>();
		if (toolContext != null && toolContext.getContext() != null) {
			executionContextMap.putAll(toolContext.getContext());
		}
		// Ensure toolcallId is set (use generated one if not in parent context)
		executionContextMap.put("toolcallId", toolCallId);
		// Ensure planDepth is set if we have it
		if (propagatedPlanDepth != null) {
			executionContextMap.put("planDepth", propagatedPlanDepth);
		}
		ToolContext executionContext = new ToolContext(executionContextMap);

		// Execute the tool
		if (executorPoolProvider != null) {
			if (isAsyncTool) {
				// Async tool with executor pool provider
				@SuppressWarnings("unchecked")
				AsyncToolCallBiFunctionDef<Object> asyncTool = (AsyncToolCallBiFunctionDef<Object>) functionInstance;
				return asyncTool.applyAsync(convertedInput, executionContext).thenApply(result -> {
					Map<String, Object> resultMap = new HashMap<>();
					if (index != null) {
						resultMap.put("index", index);
					}
					resultMap.put("status", "SUCCESS");
					resultMap.put("output", result.getOutput());
					return resultMap;
				}).exceptionally(e -> {
					logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
					Map<String, Object> errorResult = new HashMap<>();
					if (index != null) {
						errorResult.put("index", index);
					}
					errorResult.put("status", "ERROR");
					errorResult.put("error", e.getMessage());
					return errorResult;
				});
			}
			else {
				// Sync tool with executor pool provider
				return executorPoolProvider.submitTask(depthLevel, () -> {
					try {
						@SuppressWarnings("unchecked")
						ToolExecuteResult result = ((ToolCallBiFunctionDef<Object>) functionInstance)
							.apply(convertedInput, executionContext);
						Map<String, Object> resultMap = new HashMap<>();
						if (index != null) {
							resultMap.put("index", index);
						}
						resultMap.put("status", "SUCCESS");
						resultMap.put("output", result.getOutput());
						return resultMap;
					}
					catch (Exception e) {
						logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						if (index != null) {
							errorResult.put("index", index);
						}
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						return errorResult;
					}
				});
			}
		}
		else {
			// Fallback to default executor
			if (isAsyncTool) {
				@SuppressWarnings("unchecked")
				AsyncToolCallBiFunctionDef<Object> asyncTool = (AsyncToolCallBiFunctionDef<Object>) functionInstance;
				return asyncTool.applyAsync(convertedInput, executionContext).thenApply(result -> {
					Map<String, Object> resultMap = new HashMap<>();
					if (index != null) {
						resultMap.put("index", index);
					}
					resultMap.put("status", "SUCCESS");
					resultMap.put("output", result.getOutput());
					return resultMap;
				}).exceptionally(e -> {
					logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
					Map<String, Object> errorResult = new HashMap<>();
					if (index != null) {
						errorResult.put("index", index);
					}
					errorResult.put("status", "ERROR");
					errorResult.put("error", e.getMessage());
					return errorResult;
				});
			}
			else {
				return CompletableFuture.supplyAsync(() -> {
					try {
						@SuppressWarnings("unchecked")
						ToolExecuteResult result = ((ToolCallBiFunctionDef<Object>) functionInstance)
							.apply(convertedInput, executionContext);
						Map<String, Object> resultMap = new HashMap<>();
						if (index != null) {
							resultMap.put("index", index);
						}
						resultMap.put("status", "SUCCESS");
						resultMap.put("output", result.getOutput());
						return resultMap;
					}
					catch (Exception e) {
						logger.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						if (index != null) {
							errorResult.put("index", index);
						}
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						return errorResult;
					}
				});
			}
		}
	}

	/**
	 * Execute multiple tools in parallel
	 * @param executions List of execution requests (toolName and params)
	 * @param toolCallbackMap Map of tool callbacks
	 * @param toolContext Parent tool context
	 * @return CompletableFuture that completes with all results
	 */
	public CompletableFuture<List<Map<String, Object>>> executeToolsInParallel(
			List<ParallelExecutionRequest> executions, Map<String, ToolCallBackContext> toolCallbackMap,
			ToolContext toolContext) {
		// Separate terminate tools from other tools to establish happen-before
		// relationship
		// Use instanceof check instead of string comparison for accurate detection
		// Track original indices to maintain order
		List<ParallelExecutionRequest> terminateRequests = new ArrayList<>();
		List<ParallelExecutionRequest> otherRequests = new ArrayList<>();
		Map<ParallelExecutionRequest, Integer> requestIndexMap = new HashMap<>();

		for (int i = 0; i < executions.size(); i++) {
			ParallelExecutionRequest request = executions.get(i);
			requestIndexMap.put(request, i);

			// Check if tool is TerminateTool using instanceof
			String toolName = request.getToolName();
			ToolCallBackContext toolContextBackend = lookupToolContext(toolName, toolCallbackMap);
			boolean isTerminateTool = false;

			if (toolContextBackend != null) {
				ToolCallBiFunctionDef<?> functionInstance = toolContextBackend.getFunctionInstance();
				if (functionInstance instanceof TerminateTool) {
					isTerminateTool = true;
				}
			}

			if (isTerminateTool) {
				terminateRequests.add(request);
			}
			else {
				otherRequests.add(request);
			}
		}

		// Execute non-terminate tools first
		List<CompletableFuture<Map<String, Object>>> otherFutures = new ArrayList<>();
		for (ParallelExecutionRequest request : otherRequests) {
			// Create a tool-specific context that includes the toolCallId if provided
			ToolContext toolSpecificContext = toolContext;
			if (request.getToolCallId() != null) {
				Map<String, Object> contextMap = new HashMap<>();
				if (toolContext != null && toolContext.getContext() != null) {
					contextMap.putAll(toolContext.getContext());
				}
				contextMap.put("toolcallId", request.getToolCallId());
				toolSpecificContext = new ToolContext(contextMap);
			}
			// Use original index from executions list to maintain order
			int originalIndex = requestIndexMap.get(request);
			otherFutures.add(executeTool(request.getToolName(), request.getParams(), toolCallbackMap,
					toolSpecificContext, originalIndex));
		}

		// Wait for all non-terminate tools to complete (happen-before relationship)
		// Since allOf ensures all futures are complete, we can safely get results
		CompletableFuture<List<Map<String, Object>>> otherResultsFuture = CompletableFuture
			.allOf(otherFutures.toArray(new CompletableFuture[0]))
			.thenApply(v -> {
				List<Map<String, Object>> results = new ArrayList<>();
				for (CompletableFuture<Map<String, Object>> future : otherFutures) {
					try {
						// Future is already completed due to allOf, so getNow() won't
						// block
						// Use getNow with null default - if future is not done, something
						// is wrong
						Map<String, Object> result = future.getNow(null);
						if (result != null) {
							results.add(result);
						}
						else {
							logger.warn("Future result is null, creating error result");
							Map<String, Object> errorResult = new HashMap<>();
							errorResult.put("status", "ERROR");
							errorResult.put("error", "Future result is null");
							results.add(errorResult);
						}
					}
					catch (Exception e) {
						logger.error("Error getting result from future: {}", e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						results.add(errorResult);
					}
				}
				return results;
			});

		// After all other tools complete, execute terminate tools
		if (terminateRequests.isEmpty()) {
			// No terminate tools, just return other results sorted by index
			return otherResultsFuture.thenApply(results -> {
				results.sort((a, b) -> {
					Integer indexA = (Integer) a.get("index");
					Integer indexB = (Integer) b.get("index");
					if (indexA == null && indexB == null) {
						return 0;
					}
					if (indexA == null) {
						return 1;
					}
					if (indexB == null) {
						return -1;
					}
					return Integer.compare(indexA, indexB);
				});
				return results;
			});
		}

		// Execute terminate tools after all other tools complete
		return otherResultsFuture.thenCompose(otherResults -> {
			logger.info("Executing {} terminate tool(s) after all other parallel operations completed",
					terminateRequests.size());
			List<CompletableFuture<Map<String, Object>>> terminateFutures = new ArrayList<>();
			for (ParallelExecutionRequest request : terminateRequests) {
				// Create a tool-specific context that includes the toolCallId if provided
				ToolContext toolSpecificContext = toolContext;
				if (request.getToolCallId() != null) {
					Map<String, Object> contextMap = new HashMap<>();
					if (toolContext != null && toolContext.getContext() != null) {
						contextMap.putAll(toolContext.getContext());
					}
					contextMap.put("toolcallId", request.getToolCallId());
					toolSpecificContext = new ToolContext(contextMap);
				}
				// Use original index from executions list to maintain order
				int originalIndex = requestIndexMap.get(request);
				terminateFutures.add(executeTool(request.getToolName(), request.getParams(), toolCallbackMap,
						toolSpecificContext, originalIndex));
			}

			return CompletableFuture.allOf(terminateFutures.toArray(new CompletableFuture[0])).thenApply(v -> {
				List<Map<String, Object>> terminateResults = new ArrayList<>();
				for (CompletableFuture<Map<String, Object>> future : terminateFutures) {
					try {
						// Future is already completed due to allOf, so getNow() won't
						// block
						// Use getNow with null default - if future is not done, something
						// is wrong
						Map<String, Object> result = future.getNow(null);
						if (result != null) {
							terminateResults.add(result);
						}
						else {
							logger.warn("Terminate future result is null, creating error result");
							Map<String, Object> errorResult = new HashMap<>();
							errorResult.put("status", "ERROR");
							errorResult.put("error", "Future result is null");
							terminateResults.add(errorResult);
						}
					}
					catch (Exception e) {
						logger.error("Error getting terminate tool result from future: {}", e.getMessage(), e);
						Map<String, Object> errorResult = new HashMap<>();
						errorResult.put("status", "ERROR");
						errorResult.put("error", e.getMessage());
						terminateResults.add(errorResult);
					}
				}

				// Combine results from other tools and terminate tools
				List<Map<String, Object>> allResults = new ArrayList<>(otherResults);
				allResults.addAll(terminateResults);

				// Sort results by index to maintain original order
				allResults.sort((a, b) -> {
					Integer indexA = (Integer) a.get("index");
					Integer indexB = (Integer) b.get("index");
					if (indexA == null && indexB == null) {
						return 0;
					}
					if (indexA == null) {
						return 1;
					}
					if (indexB == null) {
						return -1;
					}
					return Integer.compare(indexA, indexB);
				});
				return allResults;
			});
		});
	}

	/**
	 * Get required parameter names from tool's parameter schema
	 */
	@SuppressWarnings("unchecked")
	private List<String> getRequiredParameterNames(ToolCallBiFunctionDef<?> tool) {
		try {
			String parametersSchema = tool.getParameters();
			if (parametersSchema == null || parametersSchema.trim().isEmpty()) {
				return new ArrayList<>();
			}

			// Parse JSON schema
			Map<String, Object> schema = objectMapper.readValue(parametersSchema, Map.class);

			// Handle oneOf schemas (like in parallel execution operators)
			if (schema.containsKey("oneOf")) {
				// For oneOf, we'll check all variants and collect required fields
				List<String> allRequired = new ArrayList<>();
				List<Map<String, Object>> oneOfSchemas = (List<Map<String, Object>>) schema.get("oneOf");
				for (Map<String, Object> variant : oneOfSchemas) {
					Object requiredObj = variant.get("required");
					if (requiredObj instanceof List) {
						allRequired.addAll((List<String>) requiredObj);
					}
				}
				return allRequired;
			}

			// Get required fields from schema
			Object requiredObj = schema.get("required");
			if (requiredObj instanceof List) {
				return new ArrayList<>((List<String>) requiredObj);
			}

			return new ArrayList<>();
		}
		catch (Exception e) {
			logger.debug("Could not parse required parameters from schema: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	/**
	 * Fill missing required parameters with empty string
	 */
	private Map<String, Object> fillMissingParameters(Map<String, Object> params, List<String> requiredParamNames) {
		Map<String, Object> filledParams = new HashMap<>(params);

		// Fill missing required parameters with empty string
		if (requiredParamNames != null && !requiredParamNames.isEmpty()) {
			for (String paramName : requiredParamNames) {
				if (!filledParams.containsKey(paramName)) {
					filledParams.put(paramName, "");
				}
			}
		}

		return filledParams;
	}

	/**
	 * Request for parallel execution
	 */
	public static class ParallelExecutionRequest {

		private String toolName;

		private Map<String, Object> params;

		private String toolCallId;

		public ParallelExecutionRequest() {
		}

		public ParallelExecutionRequest(String toolName, Map<String, Object> params) {
			this.toolName = toolName;
			this.params = params;
		}

		public ParallelExecutionRequest(String toolName, Map<String, Object> params, String toolCallId) {
			this.toolName = toolName;
			this.params = params;
			this.toolCallId = toolCallId;
		}

		public String getToolName() {
			return toolName;
		}

		public void setToolName(String toolName) {
			this.toolName = toolName;
		}

		public Map<String, Object> getParams() {
			return params;
		}

		public void setParams(Map<String, Object> params) {
			this.params = params;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public void setToolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
		}

	}

}

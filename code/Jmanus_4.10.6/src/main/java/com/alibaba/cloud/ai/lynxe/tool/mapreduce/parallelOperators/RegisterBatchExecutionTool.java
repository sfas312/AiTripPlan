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

import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.AsyncToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.FunctionRegistryService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Register batch execution tool that registers multiple executions of different tools in
 * parallel. Each function can have its own tool name and parameters.
 */
public class RegisterBatchExecutionTool extends AbstractBaseTool<RegisterBatchExecutionTool.RegisterBatchInput>
		implements AsyncToolCallBiFunctionDef<RegisterBatchExecutionTool.RegisterBatchInput> {

	private static final Logger logger = LoggerFactory.getLogger(RegisterBatchExecutionTool.class);

	private static final String TOOL_NAME = "register-batch-execution";

	/**
	 * Function request containing tool name and its parameters
	 */
	public static class FunctionRequest {

		@JsonProperty("tool_name")
		private String toolName;

		private Map<String, Object> params;

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

	}

	/**
	 * Input class for batch function registration
	 */
	public static class RegisterBatchInput {

		@JsonDeserialize(using = FunctionsListDeserializer.class)
		private List<FunctionRequest> functions;

		public List<FunctionRequest> getFunctions() {
			return functions;
		}

		public void setFunctions(List<FunctionRequest> functions) {
			this.functions = functions;
		}

	}

	/**
	 * Custom deserializer for functions field that handles both JSON array and JSON
	 * string formats.
	 */
	static class FunctionsListDeserializer extends JsonDeserializer<List<FunctionRequest>> {

		private static final Logger log = LoggerFactory.getLogger(FunctionsListDeserializer.class);

		private static final ObjectMapper objectMapper = new ObjectMapper();

		@Override
		public List<FunctionRequest> deserialize(JsonParser p, DeserializationContext ctxt) throws java.io.IOException {
			JsonToken currentToken = p.getCurrentToken();

			if (currentToken == JsonToken.START_ARRAY) {
				return objectMapper.readValue(p, new TypeReference<List<FunctionRequest>>() {
				});
			}

			if (currentToken == JsonToken.VALUE_STRING) {
				String stringValue = p.getText();
				try {
					JsonParser stringParser = objectMapper.getFactory().createParser(stringValue);
					return objectMapper.readValue(stringParser, new TypeReference<List<FunctionRequest>>() {
					});
				}
				catch (Exception e) {
					log.warn("Failed to parse functions from JSON string: {}", stringValue, e);
					return new ArrayList<>();
				}
			}

			if (currentToken == JsonToken.START_OBJECT) {
				try {
					FunctionRequest item = objectMapper.readValue(p, FunctionRequest.class);
					return List.of(item);
				}
				catch (Exception e) {
					log.warn("Failed to parse single object as function request", e);
					return new ArrayList<>();
				}
			}

			if (currentToken == JsonToken.VALUE_NULL) {
				return null;
			}

			log.warn("Unexpected token type for functions field: {}", currentToken);
			return new ArrayList<>();
		}

	}

	private final ObjectMapper objectMapper;

	private final PlanIdDispatcher planIdDispatcher;

	private final FunctionRegistryService functionRegistryService;

	private final ToolI18nService toolI18nService;

	public RegisterBatchExecutionTool(ObjectMapper objectMapper, PlanIdDispatcher planIdDispatcher,
			FunctionRegistryService functionRegistryService, ToolI18nService toolI18nService) {
		this.objectMapper = objectMapper;
		this.planIdDispatcher = planIdDispatcher;
		this.functionRegistryService = functionRegistryService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult apply(RegisterBatchInput input, ToolContext toolContext) {
		return applyAsync(input, toolContext).join();
	}

	@Override
	public CompletableFuture<ToolExecuteResult> applyAsync(RegisterBatchInput input, ToolContext toolContext) {
		try {
			String planId = this.currentPlanId;
			List<FunctionRegistryService.FunctionRegistry> functionRegistries = functionRegistryService
				.getRegistries(planId);

			List<FunctionRequest> functionRequests = input.getFunctions();
			if (functionRequests == null || functionRequests.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No functions provided"));
			}

			List<Map<String, Object>> registeredFunctions = new ArrayList<>();
			for (FunctionRequest functionRequest : functionRequests) {
				if (functionRequest == null) {
					logger.warn("Skipping null function request");
					continue;
				}

				String toolName = functionRequest.getToolName();
				if (toolName == null || toolName.trim().isEmpty()) {
					logger.warn("Skipping function request with missing tool_name");
					continue;
				}

				Map<String, Object> params = functionRequest.getParams();
				if (params == null) {
					params = new HashMap<>();
				}

				String funcId = planIdDispatcher.generateParallelExecutionId();
				FunctionRegistryService.FunctionRegistry function = new FunctionRegistryService.FunctionRegistry(funcId,
						toolName, params);
				functionRegistries.add(function);

				registeredFunctions
					.add(Map.of("id", funcId, "input", params, "toolName", toolName, "status", "REGISTERED"));
			}

			if (registeredFunctions.isEmpty()) {
				return CompletableFuture.completedFuture(new ToolExecuteResult("No valid functions provided"));
			}

			Map<String, Object> result = new HashMap<>();
			result.put("message", "Successfully registered " + registeredFunctions.size() + " functions");
			result.put("functions", registeredFunctions);
			try {
				return CompletableFuture
					.completedFuture(new ToolExecuteResult(objectMapper.writeValueAsString(result)));
			}
			catch (JsonProcessingException e) {
				logger.error("Error serializing result: {}", e.getMessage(), e);
				return CompletableFuture.completedFuture(
						new ToolExecuteResult("Successfully registered " + registeredFunctions.size() + " functions"));
			}
		}
		catch (Exception e) {
			logger.error("Error registering functions batch: {}", e.getMessage(), e);
			return CompletableFuture
				.completedFuture(new ToolExecuteResult("Error registering functions: " + e.getMessage()));
		}
	}

	@Override
	public ToolExecuteResult run(RegisterBatchInput input) {
		throw new UnsupportedOperationException(
				"RegisterBatchExecutionTool must be called using apply() method with ToolContext, not run()");
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
		return toolI18nService.getDescription("register-batch-execution");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("register-batch-execution");
	}

	@Override
	public Class<RegisterBatchInput> getInputType() {
		return RegisterBatchInput.class;
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

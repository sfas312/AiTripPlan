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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;

/**
 * Service to manage function registries for parallel execution tools. Stores registries
 * per planId to allow multiple tools to share state.
 */
@Service
public class FunctionRegistryService {

	private static final Logger logger = LoggerFactory.getLogger(FunctionRegistryService.class);

	/**
	 * Registry entry for a function
	 */
	public static class FunctionRegistry {

		private final String id;

		private final String toolName;

		private final Map<String, Object> input;

		private ToolExecuteResult result;

		public FunctionRegistry(String id, String toolName, Map<String, Object> input) {
			this.id = id;
			this.toolName = toolName;
			this.input = input;
		}

		public String getId() {
			return id;
		}

		public String getToolName() {
			return toolName;
		}

		public Map<String, Object> getInput() {
			return input;
		}

		public ToolExecuteResult getResult() {
			return result;
		}

		public void setResult(ToolExecuteResult result) {
			this.result = result;
		}

	}

	// Store registries per planId
	private final Map<String, List<FunctionRegistry>> registriesByPlanId = new ConcurrentHashMap<>();

	/**
	 * Get function registries for a planId
	 */
	public List<FunctionRegistry> getRegistries(String planId) {
		return registriesByPlanId.computeIfAbsent(planId, k -> new ArrayList<>());
	}

	/**
	 * Clear registries for a planId
	 */
	public void clearRegistries(String planId) {
		if (planId != null) {
			registriesByPlanId.remove(planId);
			logger.debug("Cleared function registries for plan: {}", planId);
		}
	}

}

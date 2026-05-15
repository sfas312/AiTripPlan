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
package com.alibaba.cloud.ai.lynxe.llm;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for managing token limits for different LLM models.
 */
@Service
public class TokenLimitService {

	private static final Logger log = LoggerFactory.getLogger(TokenLimitService.class);

	// Default context limit: 131,072 tokens (128K)
	private static final int DEFAULT_CONTEXT_LIMIT = 131_072;

	// Default output limit: 65,536 tokens (64K)
	private static final int DEFAULT_OUTPUT_LIMIT = 65_536;

	// Model context limits (input tokens)
	private static final Map<String, Integer> CONTEXT_LIMITS = new HashMap<>();

	// Model output limits (max tokens)
	private static final Map<String, Integer> OUTPUT_LIMITS = new HashMap<>();

	static {
		// Qwen models
		CONTEXT_LIMITS.put("qwen3-coder-plus", 1_048_576);
		CONTEXT_LIMITS.put("qwen3-coder-flash", 1_048_576);
		OUTPUT_LIMITS.put("qwen3-coder-plus", 65_536);
		OUTPUT_LIMITS.put("qwen3-coder-flash", 65_536);

		// Gemini models
		CONTEXT_LIMITS.put("gemini-1.5-pro", 2_097_152);
		CONTEXT_LIMITS.put("gemini-1.5-flash", 1_048_576);
		OUTPUT_LIMITS.put("gemini-1.5-pro", 8_192);
		OUTPUT_LIMITS.put("gemini-1.5-flash", 8_192);

		// GPT models
		CONTEXT_LIMITS.put("gpt-4o", 131_072);
		CONTEXT_LIMITS.put("gpt-4-turbo", 128_000);
		CONTEXT_LIMITS.put("gpt-4", 8_192);
		CONTEXT_LIMITS.put("gpt-3.5-turbo", 16_385);
		OUTPUT_LIMITS.put("gpt-4o", 16_384);
		OUTPUT_LIMITS.put("gpt-4-turbo", 4_096);
		OUTPUT_LIMITS.put("gpt-4", 8_192);
		OUTPUT_LIMITS.put("gpt-3.5-turbo", 4_096);
	}

	/**
	 * Get the context token limit for a given model.
	 * @param modelName The model name
	 * @return Context token limit, or default if model not found
	 */
	public int getContextLimit(String modelName) {
		if (modelName == null || modelName.trim().isEmpty()) {
			log.debug("Model name is null or empty, using default context limit: {}", DEFAULT_CONTEXT_LIMIT);
			return DEFAULT_CONTEXT_LIMIT;
		}

		// Try exact match first
		Integer limit = CONTEXT_LIMITS.get(modelName);
		if (limit != null) {
			return limit;
		}

		// Try case-insensitive match
		for (Map.Entry<String, Integer> entry : CONTEXT_LIMITS.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(modelName)) {
				return entry.getValue();
			}
		}

		// Try partial match (e.g., "gpt-4o-2024-11-20" matches "gpt-4o")
		String lowerModelName = modelName.toLowerCase();
		for (Map.Entry<String, Integer> entry : CONTEXT_LIMITS.entrySet()) {
			if (lowerModelName.startsWith(entry.getKey().toLowerCase())) {
				log.debug("Using context limit for model prefix '{}': {}", entry.getKey(), entry.getValue());
				return entry.getValue();
			}
		}

		log.debug("Model '{}' not found in context limits map, using default: {}", modelName, DEFAULT_CONTEXT_LIMIT);
		return DEFAULT_CONTEXT_LIMIT;
	}

	/**
	 * Get the maximum output token limit for a given model.
	 * @param modelName The model name
	 * @return Maximum output tokens, or default if model not found
	 */
	public int getOutputLimit(String modelName) {
		if (modelName == null || modelName.trim().isEmpty()) {
			log.debug("Model name is null or empty, using default output limit: {}", DEFAULT_OUTPUT_LIMIT);
			return DEFAULT_OUTPUT_LIMIT;
		}

		// Try exact match first
		Integer limit = OUTPUT_LIMITS.get(modelName);
		if (limit != null) {
			return limit;
		}

		// Try case-insensitive match
		for (Map.Entry<String, Integer> entry : OUTPUT_LIMITS.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(modelName)) {
				return entry.getValue();
			}
		}

		// Try partial match
		String lowerModelName = modelName.toLowerCase();
		for (Map.Entry<String, Integer> entry : OUTPUT_LIMITS.entrySet()) {
			if (lowerModelName.startsWith(entry.getKey().toLowerCase())) {
				log.debug("Using output limit for model prefix '{}': {}", entry.getKey(), entry.getValue());
				return entry.getValue();
			}
		}

		log.debug("Model '{}' not found in output limits map, using default: {}", modelName, DEFAULT_OUTPUT_LIMIT);
		return DEFAULT_OUTPUT_LIMIT;
	}

}

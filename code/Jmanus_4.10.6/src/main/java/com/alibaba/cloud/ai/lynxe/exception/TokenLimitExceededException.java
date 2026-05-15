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
package com.alibaba.cloud.ai.lynxe.exception;

/**
 * Exception thrown when token limit is exceeded before sending request to LLM.
 */
public class TokenLimitExceededException extends RuntimeException {

	private final int currentTokens;

	private final int limit;

	private final String modelName;

	public TokenLimitExceededException(int currentTokens, int limit, String modelName) {
		super(String.format("Token limit exceeded: current=%d, limit=%d, model=%s", currentTokens, limit, modelName));
		this.currentTokens = currentTokens;
		this.limit = limit;
		this.modelName = modelName;
	}

	public int getCurrentTokens() {
		return currentTokens;
	}

	public int getLimit() {
		return limit;
	}

	public String getModelName() {
		return modelName;
	}

}

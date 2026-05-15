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

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Request-scoped recorder for LLM requests and responses. Each instance tracks one
 * request/response cycle.
 */
public class LlmTraceRecorder {

	private static final Logger logger = LoggerFactory.getLogger("LLM_REQUEST_LOGGER");

	private static final Logger selfLogger = LoggerFactory.getLogger(LlmTraceRecorder.class);

	private final ObjectMapper objectMapper;

	private final String requestId;

	private TokenCountService tokenCountService;

	private Integer inputTokenCount;

	private Integer outputTokenCount;

	/**
	 * Create a new LlmTraceRecorder instance for a request
	 * @param objectMapper ObjectMapper for JSON serialization
	 */
	public LlmTraceRecorder(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.requestId = UUID.randomUUID().toString();
		this.inputTokenCount = 0;
		this.outputTokenCount = 0;
	}

	/**
	 * Create a new LlmTraceRecorder instance for a request with TokenCountService
	 * @param objectMapper ObjectMapper for JSON serialization
	 * @param tokenCountService TokenCountService for accurate token counting
	 */
	public LlmTraceRecorder(ObjectMapper objectMapper, TokenCountService tokenCountService) {
		this.objectMapper = objectMapper;
		this.tokenCountService = tokenCountService;
		this.requestId = UUID.randomUUID().toString();
		this.inputTokenCount = 0;
		this.outputTokenCount = 0;
	}

	public void recordRequest(OpenAiApi.ChatCompletionRequest chatRequest) {
		try {
			logger.info("Request[{}]: {}", requestId, objectMapper.writer().writeValueAsString(chatRequest));

			// Calculate input token count from all messages in the request
			int count = 0;
			if (chatRequest != null && chatRequest.messages() != null) {
				if (tokenCountService != null) {
					// Use TokenCountService for accurate token counting
					String requestJson = objectMapper.writer().writeValueAsString(chatRequest);
					count = tokenCountService.countTokens(requestJson);
				}
				else {
					// Fallback to approximate character-based estimation
					for (OpenAiApi.ChatCompletionMessage message : chatRequest.messages()) {
						try {
							String content = message.content();
							if (content != null) {
								count += (int) Math.ceil(content.length() / 4.0);
							}
						}
						catch (IllegalStateException e) {
							selfLogger
								.debug("Message contains non-string content (likely media), skipping token count");
						}
					}
				}
			}
			this.inputTokenCount = count;
			logger.info("Request[{}] InputTokenCount: {}", requestId, count);
		}
		catch (Throwable e) {
			selfLogger.error("Failed to serialize chat request", e);
		}
	}

	public void recordResponse(ChatResponse chatResponse) {
		try {
			String responseJson = objectMapper.writer().writeValueAsString(chatResponse);
			logger.info("Response[{}]: {}", requestId, objectMapper.writer().writeValueAsString(chatResponse));

			// Calculate output token count
			if (tokenCountService != null) {
				this.outputTokenCount = tokenCountService.countTokens(responseJson);
			}
			else {
				// Fallback to approximate character-based estimation
				this.outputTokenCount = (int) Math.ceil(responseJson.length() / 4.0);
			}
			logger.info("Response[{}] OutputTokenCount: {}", requestId, this.outputTokenCount);
		}
		catch (Throwable e) {
			selfLogger.error("Failed to serialize chat response", e);
		}
	}

	/**
	 * Record error response from API
	 * @param error The exception that occurred
	 */
	public void recordError(Throwable error) {
		try {
			if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientException) {
				String errorDetails = String.format("Error[%s]: Status=%s, ResponseBody=%s, URL=%s", requestId,
						webClientException.getStatusCode(), webClientException.getResponseBodyAsString(),
						webClientException.getRequest() != null ? webClientException.getRequest().getURI() : "N/A");
				logger.error(errorDetails);
			}
			else {
				logger.error("Error[{}]: {}", requestId, error.getMessage());
			}
		}
		catch (Throwable e) {
			selfLogger.error("Failed to record error", e);
		}
	}

	/**
	 * Get the request ID for this recorder instance
	 * @return Request ID
	 */
	public String getRequestId() {
		return requestId;
	}

	/**
	 * Set input token count (can be called if count is calculated elsewhere)
	 * @param count Input token count
	 */
	public void setInputTokenCount(int count) {
		this.inputTokenCount = count;
	}

	/**
	 * Get input token count for this request
	 * @return Input token count, or 0 if not available
	 */
	public int getInputTokenCount() {
		return inputTokenCount != null ? inputTokenCount : 0;
	}

	/**
	 * Get output token count for this request
	 * @return Output token count, or 0 if not available
	 */
	public int getOutputTokenCount() {
		return outputTokenCount != null ? outputTokenCount : 0;
	}

	/**
	 * @deprecated Use setInputTokenCount() instead. This method is kept for backward
	 * compatibility.
	 */
	@Deprecated
	public void setInputCharCount(int count) {
		this.inputTokenCount = count;
	}

	/**
	 * @deprecated Use getInputTokenCount() instead. This method is kept for backward
	 * compatibility.
	 */
	@Deprecated
	public int getInputCharCount() {
		return inputTokenCount != null ? inputTokenCount : 0;
	}

	/**
	 * @deprecated Use getOutputTokenCount() instead. This method is kept for backward
	 * compatibility.
	 */
	@Deprecated
	public int getOutputCharCount() {
		return outputTokenCount != null ? outputTokenCount : 0;
	}

}

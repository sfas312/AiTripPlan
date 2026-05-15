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
package com.alibaba.cloud.ai.lynxe.agent.entity;

import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

/**
 * Data container for agent streaming response results. Extracts commonly used data from
 * StreamingResponseHandler.StreamingResult for easier access in DynamicAgent.
 */
public class AgentStreamingResult {

	private List<ToolCall> toolCalls;

	private String responseText;

	private int inputTokenCount;

	private int outputTokenCount;

	public AgentStreamingResult() {
		this.toolCalls = Collections.emptyList();
		this.responseText = "";
		this.inputTokenCount = 0;
		this.outputTokenCount = 0;
	}

	public AgentStreamingResult(List<ToolCall> toolCalls, String responseText, int inputTokenCount,
			int outputTokenCount) {
		this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
		this.responseText = responseText != null ? responseText : "";
		this.inputTokenCount = inputTokenCount;
		this.outputTokenCount = outputTokenCount;
	}

	/**
	 * Create an AssistantMessage from this result
	 * @return AssistantMessage with tool calls and text content
	 */
	public AssistantMessage createAssistantMessage() {
		return AssistantMessage.builder().content(responseText).toolCalls(toolCalls).build();
	}

	/**
	 * Get effective tool calls from the response
	 * @return List of ToolCall objects
	 */
	public List<ToolCall> getToolCalls() {
		return toolCalls;
	}

	/**
	 * Set tool calls
	 * @param toolCalls List of ToolCall objects
	 */
	public void setToolCalls(List<ToolCall> toolCalls) {
		this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
	}

	/**
	 * Get effective text content from the response
	 * @return Text content as String
	 */
	public String getResponseText() {
		return responseText;
	}

	/**
	 * Set response text
	 * @param responseText Text content as String
	 */
	public void setResponseText(String responseText) {
		this.responseText = responseText != null ? responseText : "";
	}

	/**
	 * Get input token count
	 * @return Number of tokens in the input
	 */
	public int getInputTokenCount() {
		return inputTokenCount;
	}

	/**
	 * Set input token count
	 * @param inputTokenCount Number of tokens in the input
	 */
	public void setInputTokenCount(int inputTokenCount) {
		this.inputTokenCount = inputTokenCount;
	}

	/**
	 * Get output token count
	 * @return Number of tokens in the output
	 */
	public int getOutputTokenCount() {
		return outputTokenCount;
	}

	/**
	 * Set output token count
	 * @param outputTokenCount Number of tokens in the output
	 */
	public void setOutputTokenCount(int outputTokenCount) {
		this.outputTokenCount = outputTokenCount;
	}

	/**
	 * Check if there are any tool calls
	 * @return true if toolCalls list is not empty
	 */
	public boolean hasToolCalls() {
		return toolCalls != null && !toolCalls.isEmpty();
	}

	/**
	 * Check if response text is not empty
	 * @return true if responseText is not null and not empty
	 */
	public boolean hasResponseText() {
		return responseText != null && !responseText.trim().isEmpty();
	}

}

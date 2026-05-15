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
package com.alibaba.cloud.ai.lynxe.agent.fix;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;

/**
 * StreamAdvisor to fix duplicate tool calls in streaming responses.
 *
 * This advisor merges tool calls with the same ID that are split across multiple chunks
 * in streaming responses. It ensures that each tool call has complete fields (id, name,
 * arguments).
 */
public class DynamicAgentStreamingFix implements StreamAdvisor {

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {

		Flux<ChatClientResponse> responseFlux = streamAdvisorChain.nextStream(chatClientRequest);

		// Fix tool calls in each chunk
		return responseFlux.map(response -> {
			if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
				ChatResponse fixedResponse = mergeToolCalls(response.chatResponse());
				return response.mutate().chatResponse(fixedResponse).build();
			}
			return response;
		});
	}

	private ChatResponse mergeToolCalls(ChatResponse chatResponse) {
		List<Generation> fixedGenerations = chatResponse.getResults().stream().map(generation -> {
			AssistantMessage output = generation.getOutput();

			if (output.hasToolCalls()) {
				List<AssistantMessage.ToolCall> mergedToolCalls = mergeToolCalls(output.getToolCalls());

				AssistantMessage fixedOutput = AssistantMessage.builder()
					.content(output.getText())
					.properties(output.getMetadata())
					.toolCalls(mergedToolCalls)
					.build();

				return new Generation(fixedOutput, generation.getMetadata());
			}

			return generation;
		}).toList();

		return ChatResponse.builder().from(chatResponse).generations(fixedGenerations).build();
	}

	private List<AssistantMessage.ToolCall> mergeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}

		Map<String, AssistantMessage.ToolCall> mergedMap = new LinkedHashMap<>();

		for (AssistantMessage.ToolCall call : toolCalls) {
			String id = call.id();

			if (!StringUtils.hasText(id)) {
				continue;
			}

			AssistantMessage.ToolCall existing = mergedMap.get(id);

			if (existing != null) {
				// Merge fields: use non-empty values
				String mergedName = StringUtils.hasText(existing.name()) ? existing.name() : call.name();
				String mergedArguments = StringUtils.hasText(existing.arguments()) ? existing.arguments()
						: call.arguments();

				String mergedType = existing.type() != null && StringUtils.hasText(existing.type()) ? existing.type()
						: (call.type() != null ? call.type() : "function");

				AssistantMessage.ToolCall merged = new AssistantMessage.ToolCall(id, mergedType, mergedName,
						mergedArguments);

				mergedMap.put(id, merged);
			}
			else {
				mergedMap.put(id, call);
			}
		}

		// Filter out incomplete tool calls (missing id, name, or arguments)
		return mergedMap.values()
			.stream()
			.filter(call -> StringUtils.hasText(call.id()) && StringUtils.hasText(call.name())
					&& StringUtils.hasText(call.arguments()))
			.toList();
	}

	@Override
	public String getName() {
		return "dynamicAgentStreamingFix";
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 100;
	}

}

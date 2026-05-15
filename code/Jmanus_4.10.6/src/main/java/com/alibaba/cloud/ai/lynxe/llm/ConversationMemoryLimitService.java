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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service to automatically limit conversation memory size based on token count. Uses LLM
 * to summarize older dialog rounds while maintaining recent 5000 characters.
 *
 * @author lynxe
 */
@Service
public class ConversationMemoryLimitService {

	private static final Logger log = LoggerFactory.getLogger(ConversationMemoryLimitService.class);

	// Default retention ratio: 30% (configurable via LynxeProperties)
	private static final double DEFAULT_RETENTION_RATIO = 0.3;

	// Default compression threshold: 70% (configurable via LynxeProperties)
	private static final double DEFAULT_COMPRESSION_THRESHOLD = 0.7;

	private static final String COMPRESSION_CONFIRMATION_MESSAGE = "Got it. Thanks for the additional context!";

	/**
	 * Metadata key to mark compression summary messages. Messages with this metadata
	 * should be preserved in agent memory even though they are UserMessages.
	 */
	public static final String COMPRESSION_SUMMARY_METADATA_KEY = "compression_summary";

	@Autowired
	private LynxeProperties lynxeProperties;

	@Autowired
	private LlmService llmService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TokenCountService tokenCountService;

	@Autowired(required = false)
	private TokenLimitService tokenLimitService;

	/**
	 * Check and limit conversation memory size for a given conversation ID. Maintains
	 * recent 5000 chars (at least one complete dialog round) and summarizes older rounds
	 * into a 3000-4000 char UserMessage.
	 * @param chatMemory The chat memory instance
	 * @param conversationId The conversation ID to check and limit
	 */
	public void checkAndLimitMemory(ChatMemory chatMemory, String conversationId) {
		if (chatMemory == null || conversationId == null || conversationId.trim().isEmpty()) {
			return;
		}

		try {
			List<Message> messages = chatMemory.get(conversationId);
			if (messages == null || messages.isEmpty()) {
				return;
			}

			int totalTokens = calculateTotalTokens(messages);
			int maxTokens = getMaxTokenCount();

			// Get compression threshold (default 70%)
			double compressionThreshold = getCompressionThreshold();
			int thresholdTokens = (int) (maxTokens * compressionThreshold);

			// Trigger compression when reaching threshold (proactive)
			if (totalTokens <= thresholdTokens) {
				log.debug(
						"Conversation memory size ({} tokens) is within compression threshold ({} tokens, {}%) for conversationId: {}",
						totalTokens, thresholdTokens, (int) (compressionThreshold * 100), conversationId);
				return;
			}

			log.info(
					"Conversation memory size ({} tokens) exceeds compression threshold ({} tokens, {}% of limit {}) for conversationId: {}. Summarizing older messages...",
					totalTokens, thresholdTokens, (int) (compressionThreshold * 100), maxTokens, conversationId);

			// Summarize and trim messages
			summarizeAndTrimMessages(chatMemory, conversationId, messages);

		}
		catch (Exception e) {
			log.warn("Failed to check and limit conversation memory for conversationId: {}", conversationId, e);
		}
	}

	/**
	 * Calculate total token count of all messages using TokenCountService. This gives a
	 * more accurate count of the actual data that would be sent to LLM.
	 * @param messages List of messages
	 * @return Total token count
	 * @throws IllegalStateException if TokenCountService is not available
	 */
	public int calculateTotalTokens(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}

		if (tokenCountService == null) {
			throw new IllegalStateException(
					"TokenCountService is not available. Cannot calculate token count for messages.");
		}

		return tokenCountService.countTokens(messages);
	}

	/**
	 * Get the maximum token count limit from TokenLimitService based on the default
	 * model.
	 * @return Maximum token count for the current model
	 * @throws IllegalStateException if TokenLimitService or model name is not available
	 */
	private int getMaxTokenCount() {
		if (tokenLimitService == null) {
			throw new IllegalStateException(
					"TokenLimitService is not available. Cannot get token limit for conversation memory compression.");
		}

		if (llmService == null) {
			throw new IllegalStateException(
					"LlmService is not available. Cannot get default model name for token limit calculation.");
		}

		String modelName = llmService.getDefaultModelName();
		if (modelName == null || modelName.trim().isEmpty()) {
			throw new IllegalStateException(
					"Default model name is not available. Cannot get token limit for conversation memory compression.");
		}

		int modelLimit = tokenLimitService.getContextLimit(modelName);
		log.debug("Using model-specific token limit for model '{}': {}", modelName, modelLimit);
		return modelLimit;
	}

	/**
	 * Get the compression threshold ratio from configuration.
	 * @return Compression threshold ratio (default: 0.7 = 70%)
	 */
	private double getCompressionThreshold() {
		if (lynxeProperties == null) {
			return DEFAULT_COMPRESSION_THRESHOLD;
		}
		Double threshold = lynxeProperties.getChatCompressionThreshold();
		return threshold != null && threshold > 0 && threshold <= 1.0 ? threshold : DEFAULT_COMPRESSION_THRESHOLD;
	}

	/**
	 * Get the retention ratio from configuration.
	 * @return Retention ratio (default: 0.3 = 30%)
	 */
	private double getRetentionRatio() {
		if (lynxeProperties == null) {
			return DEFAULT_RETENTION_RATIO;
		}
		Double ratio = lynxeProperties.getChatCompressionRetentionRatio();
		return ratio != null && ratio > 0 && ratio <= 1.0 ? ratio : DEFAULT_RETENTION_RATIO;
	}

	/**
	 * Summarize and trim messages: retain configurable ratio (default 30%) of content (by
	 * token count), ensuring at least one complete round is kept. Summarize older rounds
	 * into a summary UserMessage.
	 * @param chatMemory The chat memory instance
	 * @param conversationId The conversation ID
	 * @param messages Current list of messages
	 */
	private void summarizeAndTrimMessages(ChatMemory chatMemory, String conversationId, List<Message> messages) {
		// Group messages into dialog rounds (UserMessage + AssistantMessage pairs)
		List<DialogRound> dialogRounds = groupMessagesIntoRounds(messages);

		if (dialogRounds.isEmpty()) {
			log.warn("No dialog rounds found for conversationId: {}", conversationId);
			return;
		}

		// Calculate total token count of all rounds
		int totalChars = dialogRounds.stream().mapToInt(round -> round.getTotalChars(objectMapper)).sum();

		// Calculate target retention: configurable ratio (default 30%) of total content
		double retentionRatio = getRetentionRatio();
		int targetRetentionChars = (int) (totalChars * retentionRatio);

		// If total is very small, keep all rounds
		if (totalChars <= 0 || targetRetentionChars <= 0) {
			log.debug("Total token count ({}) is too small, keeping all rounds for conversationId: {}", totalChars,
					conversationId);
			return;
		}

		// Find which rounds to keep and which to summarize
		// Strategy: Keep rounds from newest to oldest until accumulated chars reach
		// retention ratio
		List<DialogRound> roundsToKeep = new ArrayList<>();
		List<DialogRound> roundsToSummarize = new ArrayList<>();

		int accumulatedChars = 0;
		boolean hasKeptAtLeastOneRound = false;

		// Start from the newest round and work backwards
		for (int i = dialogRounds.size() - 1; i >= 0; i--) {
			DialogRound round = dialogRounds.get(i);
			int roundChars = round.getTotalChars(objectMapper);

			// Always keep at least the newest round (even if it exceeds retention ratio)
			if (i == dialogRounds.size() - 1) {
				roundsToKeep.add(0, round);
				accumulatedChars += roundChars;
				hasKeptAtLeastOneRound = true;
			}
			else {
				// For other rounds, check if we can add them within retention ratio limit
				if (accumulatedChars + roundChars <= targetRetentionChars) {
					roundsToKeep.add(0, round); // Add at beginning to maintain
												// chronological order
					accumulatedChars += roundChars;
					hasKeptAtLeastOneRound = true;
				}
				else {
					// Can't add this round, all remaining are older and should be
					// summarized
					for (int j = i; j >= 0; j--) {
						roundsToSummarize.add(0, dialogRounds.get(j));
					}
					break;
				}
			}
		}

		// Ensure we kept at least one round (fallback if somehow no rounds were kept)
		if (!hasKeptAtLeastOneRound && !dialogRounds.isEmpty()) {
			DialogRound newestRound = dialogRounds.get(dialogRounds.size() - 1);
			roundsToKeep.add(newestRound);
			// Add all others to summarize
			for (int i = 0; i < dialogRounds.size() - 1; i++) {
				roundsToSummarize.add(dialogRounds.get(i));
			}
		}

		// Summarize older rounds
		UserMessage summaryMessage = null;
		if (!roundsToSummarize.isEmpty()) {
			summaryMessage = summarizeRounds(roundsToSummarize);
		}

		// Rebuild memory: summary first (as UserMessage), then confirmation (as
		// AssistantMessage), then recent rounds
		// This maintains the user-assistant message pair pattern similar to
		// state_snapshot storage
		chatMemory.clear(conversationId);

		if (summaryMessage != null) {
			// Add summary as UserMessage (like state_snapshot)
			chatMemory.add(conversationId, summaryMessage);
			// Add confirmation AssistantMessage to maintain user-assistant pair pattern
			AssistantMessage confirmationMessage = new AssistantMessage(COMPRESSION_CONFIRMATION_MESSAGE);
			chatMemory.add(conversationId, confirmationMessage);

		}

		// Add recent rounds
		for (DialogRound round : roundsToKeep) {
			for (Message message : round.getMessages()) {
				chatMemory.add(conversationId, message);
			}
		}

		int keptTokens = calculateTotalTokens(
				roundsToKeep.stream().flatMap(round -> round.getMessages().stream()).toList());
		int totalTokens = calculateTotalTokens(messages);
		double actualRetentionRatio = totalTokens > 0 ? (double) keptTokens / totalTokens : 0.0;
		int summaryTokens = summaryMessage != null ? calculateTotalTokens(List.of(summaryMessage)) : 0;
		log.info(
				"Summarized conversation memory for conversationId: {}. Kept {} recent rounds ({} tokens, {:.1f}% retention), summarized {} older rounds into {} tokens",
				conversationId, roundsToKeep.size(), keptTokens, String.format("%.1f", actualRetentionRatio * 100),
				roundsToSummarize.size(), summaryTokens);
	}

	/**
	 * Group messages into dialog rounds. Supports three grouping scenarios:
	 * <ol>
	 * <li>UserMessage -> AssistantMessage -> ToolResponseMessage (complete round with
	 * tool call)</li>
	 * <li>UserMessage -> AssistantMessage (round without tool call)</li>
	 * <li>AssistantMessage -> ToolResponseMessage (agent memory scenario)</li>
	 * </ol>
	 * @param messages List of messages
	 * @return List of dialog rounds
	 */
	private List<DialogRound> groupMessagesIntoRounds(List<Message> messages) {
		List<DialogRound> rounds = new ArrayList<>();
		DialogRound currentRound = null;

		for (Message message : messages) {
			if (message instanceof UserMessage) {
				// Scenario: UserMessage starts a new round
				// Can be followed by AssistantMessage (with or without
				// ToolResponseMessage)
				// Complete previous round if exists
				if (currentRound != null) {
					rounds.add(currentRound);
				}
				// Start new round with UserMessage
				currentRound = new DialogRound();
				currentRound.addMessage(message);
			}
			else if (message instanceof AssistantMessage) {
				// Check if current round has UserMessage (Scenario 2: UserMessage ->
				// AssistantMessage)
				// or if it's a standalone AssistantMessage (Scenario 3: AssistantMessage
				// -> ToolResponseMessage)
				if (currentRound != null) {
					// Check if current round already has a UserMessage
					boolean hasUserMessage = currentRound.getMessages()
						.stream()
						.anyMatch(msg -> msg instanceof UserMessage);
					if (hasUserMessage) {
						// Scenario 2: UserMessage -> AssistantMessage
						// Add AssistantMessage to current round (round may complete here
						// or wait for ToolResponseMessage)
						currentRound.addMessage(message);
					}
					else {
						// Current round doesn't have UserMessage, complete it and start
						// new round
						rounds.add(currentRound);
						currentRound = new DialogRound();
						currentRound.addMessage(message);
					}
				}
				else {
					// Scenario 3: AssistantMessage -> ToolResponseMessage (agent memory
					// scenario)
					// Start new round with AssistantMessage
					currentRound = new DialogRound();
					currentRound.addMessage(message);
				}
			}
			else if (message instanceof ToolResponseMessage) {
				// ToolResponseMessage completes a round
				// Can be part of:
				// - Scenario 1: UserMessage -> AssistantMessage -> ToolResponseMessage
				// - Scenario 3: AssistantMessage -> ToolResponseMessage
				if (currentRound == null) {
					// No current round, create one (edge case)
					currentRound = new DialogRound();
				}
				currentRound.addMessage(message);
				// Round is complete, add it to rounds
				rounds.add(currentRound);
				currentRound = null;
			}
			else {
				// Other message types, add to current round if exists
				if (currentRound != null) {
					currentRound.addMessage(message);
				}
			}
		}

		// Add the last round if it exists and wasn't completed
		// This handles incomplete rounds like UserMessage -> AssistantMessage (Scenario
		// 2)
		if (currentRound != null) {
			rounds.add(currentRound);
		}

		return rounds;
	}

	/**
	 * Summarize multiple dialog rounds into a single UserMessage in state_snapshot XML
	 * format. The summary should be between 3000-4000 chars and structured as
	 * state_snapshot XML.
	 * @param rounds Dialog rounds to summarize
	 * @return Summarized UserMessage in state_snapshot XML format
	 */
	private UserMessage summarizeRounds(List<DialogRound> rounds) {
		try {
			// Build list of all messages from rounds
			List<Message> allMessages = new ArrayList<>();
			for (DialogRound round : rounds) {
				allMessages.addAll(round.getMessages());
			}

			// Convert entire message list to JSON as conversation text
			String conversationHistory;
			try {
				conversationHistory = objectMapper.writeValueAsString(allMessages);
			}
			catch (Exception e) {
				log.error("Failed to serialize messages to JSON for summarization", e);
				throw new IllegalStateException(
						"Failed to serialize messages to JSON for summarization: " + e.getMessage(), e);
			}

			// Create summarization prompt with state_snapshot XML format requirement
			String summaryPrompt = String.format(
					"""
							First, reason in your scratchpad. Then, generate the <state_snapshot>.

							Analyze the following conversation history and create a structured state_snapshot XML.

							Required XML structure:
							<state_snapshot>
							<key_knowledge>
							[Important facts, commands, configurations, URLs, file paths, and key information discovered]
							</key_knowledge>
							<previous_actions_summary>
							[Briefly summarize what the system has already done previously]
							</previous_actions_summary>
							<recent_actions>
							[Recent tool calls, commands executed, searches performed, and actions taken]
							</recent_actions>
							<current_plan>
							[Current plan items with status: [DONE], [IN PROGRESS], [PENDING]]
							</current_plan>
							</state_snapshot>

							Guidelines:
							- ALL XML tags are REQUIRED and MUST contain content. Each tag must have meaningful content, cannot be empty.
							- Preserve all critical information: URLs, file paths, commands, configurations
							- Include tool names and their results when relevant
							- Maintain plan status and progress
							- Output the XML content directly, no additional text before or after

							Conversation history:
							%s
							""",
					conversationHistory);

			// Use LLM to generate summary in state_snapshot format
			ChatClient chatClient = llmService.getDefaultDynamicAgentChatClient();
			ChatResponse response = chatClient.prompt()
				.system("You are a helpful assistant that creates structured state_snapshot summaries. "
						+ "Always output valid XML in the exact format requested.")
				.user(summaryPrompt)
				.call()
				.chatResponse();

			String summary = response.getResult().getOutput().getText();

			// Add prefix explanation before the summary content
			String prefixExplanation = "The following content is a brief summary of previously executed actions. "
					+ "The original content was too long and has been summarized:\n\n";
			String finalSummary = prefixExplanation + summary;

			// Store as UserMessage regardless of format correctness (as requested)
			// Add metadata to mark this as a compression summary so it won't be filtered
			// out by processMemory
			UserMessage summaryMessage = new UserMessage(finalSummary);
			summaryMessage.getMetadata().put(COMPRESSION_SUMMARY_METADATA_KEY, Boolean.TRUE);
			return summaryMessage;

		}
		catch (Exception e) {
			log.error("Failed to summarize dialog rounds", e);
			throw new IllegalStateException("Failed to summarize dialog rounds: " + e.getMessage(), e);
		}
	}

	/**
	 * Inner class to represent a dialog round. For conversation memory: typically
	 * UserMessage + AssistantMessage pairs For agent memory: typically AssistantMessage
	 * (with tool calls) + ToolResponseMessage pairs
	 */
	private static class DialogRound {

		private final List<Message> messages = new ArrayList<>();

		public void addMessage(Message message) {
			messages.add(message);
		}

		public List<Message> getMessages() {
			return messages;
		}

		public int getTotalChars(ObjectMapper objectMapper) {
			if (messages == null || messages.isEmpty()) {
				return 0;
			}
			try {
				// Serialize messages to JSON to get accurate token count
				String json = objectMapper.writeValueAsString(messages);
				return json.length();
			}
			catch (Exception e) {
				log.error("Failed to serialize messages to JSON for token count calculation", e);
				throw new IllegalStateException(
						"Failed to serialize messages to JSON for token count calculation: " + e.getMessage(), e);
			}
		}

	}

	/**
	 * Force compress conversation memory to break potential loops. This method compresses
	 * the memory regardless of token count limits, keeping only the most recent round and
	 * summarizing all older rounds.
	 * @param chatMemory The chat memory instance
	 * @param conversationId The conversation ID to compress memory for
	 */
	public void forceCompressConversationMemory(ChatMemory chatMemory, String conversationId) {
		if (chatMemory == null || conversationId == null || conversationId.trim().isEmpty()) {
			return;
		}

		try {
			List<Message> messages = chatMemory.get(conversationId);
			if (messages == null || messages.isEmpty()) {
				log.debug("No messages found for conversationId: {}, skipping forced compression", conversationId);
				return;
			}

			log.info(
					"Force compressing conversation memory for conversationId: {} to break potential loop. Message count: {}",
					conversationId, messages.size());

			// Group messages into dialog rounds
			List<DialogRound> dialogRounds = groupMessagesIntoRounds(messages);

			if (dialogRounds.isEmpty()) {
				log.warn("No dialog rounds found for conversationId: {}", conversationId);
				return;
			}

			// Calculate total token count of all rounds
			int totalChars = dialogRounds.stream().mapToInt(round -> round.getTotalChars(objectMapper)).sum();

			// Calculate target retention: configurable ratio (default 30%) of total
			// content
			double retentionRatio = getRetentionRatio();
			int targetRetentionChars = (int) (totalChars * retentionRatio);

			// If total is very small, keep all rounds
			if (totalChars <= 0 || targetRetentionChars <= 0) {
				log.debug("Total character count ({}) is too small, keeping all rounds for conversationId: {}",
						totalChars, conversationId);
				return;
			}

			// Force compression: keep rounds from newest to oldest until accumulated
			// chars reach retention ratio (default 30%)
			List<DialogRound> roundsToKeep = new ArrayList<>();
			List<DialogRound> roundsToSummarize = new ArrayList<>();

			int accumulatedChars = 0;
			boolean hasKeptAtLeastOneRound = false;

			// Start from the newest round and work backwards
			for (int i = dialogRounds.size() - 1; i >= 0; i--) {
				DialogRound round = dialogRounds.get(i);
				int roundChars = round.getTotalChars(objectMapper);

				// Always keep at least the newest round (even if it exceeds 40%)
				if (i == dialogRounds.size() - 1) {
					roundsToKeep.add(round);
					accumulatedChars += roundChars;
					hasKeptAtLeastOneRound = true;
				}
				else {
					// For other rounds, check if we can add them within 40% retention
					// limit
					if (accumulatedChars + roundChars <= targetRetentionChars) {
						roundsToKeep.add(0, round); // Add at beginning to maintain
													// chronological order
						accumulatedChars += roundChars;
						hasKeptAtLeastOneRound = true;
					}
					else {
						// Can't add this round, all remaining are older and should be
						// summarized
						for (int j = i; j >= 0; j--) {
							roundsToSummarize.add(0, dialogRounds.get(j));
						}
						break;
					}
				}
			}

			// Ensure we kept at least one round (fallback if somehow no rounds were kept)
			if (!hasKeptAtLeastOneRound && !dialogRounds.isEmpty()) {
				roundsToKeep.add(dialogRounds.get(dialogRounds.size() - 1));
				// Add all others to summarize
				for (int i = 0; i < dialogRounds.size() - 1; i++) {
					roundsToSummarize.add(dialogRounds.get(i));
				}
			}

			// Summarize older rounds
			UserMessage summaryMessage = null;
			if (!roundsToSummarize.isEmpty()) {
				summaryMessage = summarizeRounds(roundsToSummarize);
			}

			// Rebuild memory: summary first (as UserMessage), then confirmation (as
			// AssistantMessage), then most recent round
			// This maintains the user-assistant message pair pattern similar to
			// state_snapshot storage
			chatMemory.clear(conversationId);

			if (summaryMessage != null) {
				// Add summary as UserMessage (like state_snapshot)
				chatMemory.add(conversationId, summaryMessage);
				// Add confirmation AssistantMessage to maintain user-assistant pair
				// pattern
				AssistantMessage confirmationMessage = new AssistantMessage(COMPRESSION_CONFIRMATION_MESSAGE);
				chatMemory.add(conversationId, confirmationMessage);
				int summaryTokens = calculateTotalTokens(List.of(summaryMessage));
				log.info("Added forced summary message ({} tokens) with confirmation for conversationId: {}",
						summaryTokens, conversationId);
			}

			// Add most recent round
			for (DialogRound round : roundsToKeep) {
				for (Message message : round.getMessages()) {
					chatMemory.add(conversationId, message);
				}
			}

			int keptTokens = calculateTotalTokens(
					roundsToKeep.stream().flatMap(round -> round.getMessages().stream()).toList());
			int totalTokens = calculateTotalTokens(messages);
			double actualRetentionRatio = totalTokens > 0 ? (double) keptTokens / totalTokens : 0.0;
			int summaryTokens = summaryMessage != null ? calculateTotalTokens(List.of(summaryMessage)) : 0;
			log.info(
					"Forced compression completed for conversationId: {}. Kept {} recent round(s) ({} tokens, {}% retention), summarized {} older rounds into {} tokens",
					conversationId, roundsToKeep.size(), keptTokens, String.format("%.1f", actualRetentionRatio * 100),
					roundsToSummarize.size(), summaryTokens);
		}
		catch (Exception e) {
			log.warn("Failed to force compress conversation memory for conversationId: {}", conversationId, e);
		}
	}

	/**
	 * Force compress agent memory to break potential loops caused by repeated tool call
	 * results. This method compresses the memory regardless of token count limits.
	 * @param messages The list of messages to compress
	 * @return Compressed list of messages containing summary and most recent round
	 */
	public List<Message> forceCompressAgentMemory(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			log.debug("No messages found, skipping forced compression");
			return new ArrayList<>(messages);
		}

		try {
			log.info("Force compressing agent memory to break potential loop. Message count: {}", messages.size());

			// Group messages into dialog rounds
			List<DialogRound> dialogRounds = groupMessagesIntoRounds(messages);

			if (dialogRounds.isEmpty()) {
				log.warn("No dialog rounds found, returning original messages");
				return new ArrayList<>(messages);
			}

			// Calculate total token count of all rounds
			int totalChars = dialogRounds.stream().mapToInt(round -> round.getTotalChars(objectMapper)).sum();

			// Calculate target retention: configurable ratio (default 30%) of total
			// content
			double retentionRatio = getRetentionRatio();
			int targetRetentionChars = (int) (totalChars * retentionRatio);

			// If total is very small, keep all rounds
			if (totalChars <= 0 || targetRetentionChars <= 0) {
				log.debug("Total character count ({}) is too small, keeping all rounds", totalChars);
				return new ArrayList<>(messages);
			}

			// Force compression: keep rounds from newest to oldest until accumulated
			// chars reach retention ratio (default 30%)
			List<DialogRound> roundsToKeep = new ArrayList<>();
			List<DialogRound> roundsToSummarize = new ArrayList<>();

			int accumulatedChars = 0;
			boolean hasKeptAtLeastOneRound = false;

			// Start from the newest round and work backwards
			for (int i = dialogRounds.size() - 1; i >= 0; i--) {
				DialogRound round = dialogRounds.get(i);
				int roundChars = round.getTotalChars(objectMapper);

				// Always keep at least the newest round (even if it exceeds 40%)
				if (i == dialogRounds.size() - 1) {
					roundsToKeep.add(round);
					accumulatedChars += roundChars;
					hasKeptAtLeastOneRound = true;
				}
				else {
					// For other rounds, check if we can add them within 40% retention
					// limit
					if (accumulatedChars + roundChars <= targetRetentionChars) {
						roundsToKeep.add(0, round); // Add at beginning to maintain
													// chronological order
						accumulatedChars += roundChars;
						hasKeptAtLeastOneRound = true;
					}
					else {
						// Can't add this round, all remaining are older and should be
						// summarized
						for (int j = i; j >= 0; j--) {
							roundsToSummarize.add(0, dialogRounds.get(j));
						}
						break;
					}
				}
			}

			// Ensure we kept at least one round (fallback if somehow no rounds were kept)
			if (!hasKeptAtLeastOneRound && !dialogRounds.isEmpty()) {
				roundsToKeep.add(dialogRounds.get(dialogRounds.size() - 1));
				// Add all others to summarize
				for (int i = 0; i < dialogRounds.size() - 1; i++) {
					roundsToSummarize.add(dialogRounds.get(i));
				}
			}

			// Summarize older rounds
			UserMessage summaryMessage = null;
			if (!roundsToSummarize.isEmpty()) {
				summaryMessage = summarizeRounds(roundsToSummarize);
			}

			// Build compressed message list: summary first (as UserMessage), then
			// confirmation (as AssistantMessage), then most recent round
			// This maintains the user-assistant message pair pattern similar to
			// state_snapshot storage
			List<Message> compressedMessages = new ArrayList<>();

			if (summaryMessage != null) {
				// Add summary as UserMessage (like state_snapshot)
				compressedMessages.add(summaryMessage);
				// Add confirmation AssistantMessage to maintain user-assistant pair
				// pattern
				AssistantMessage confirmationMessage = new AssistantMessage(COMPRESSION_CONFIRMATION_MESSAGE);
				compressedMessages.add(confirmationMessage);
				int summaryTokens = calculateTotalTokens(List.of(summaryMessage));
				log.info("Added forced summary message ({} tokens) with confirmation", summaryTokens);
			}

			// Add most recent round
			for (DialogRound round : roundsToKeep) {
				compressedMessages.addAll(round.getMessages());
			}

			int keptTokens = calculateTotalTokens(
					roundsToKeep.stream().flatMap(round -> round.getMessages().stream()).toList());
			int totalTokens = calculateTotalTokens(messages);
			double actualRetentionRatio = totalTokens > 0 ? (double) keptTokens / totalTokens : 0.0;
			int summaryTokens = summaryMessage != null ? calculateTotalTokens(List.of(summaryMessage)) : 0;
			log.info(
					"Forced compression completed. Kept {} recent round(s) ({} tokens, {}% retention), summarized {} older rounds into {} tokens",
					roundsToKeep.size(), keptTokens, String.format("%.1f", actualRetentionRatio * 100),
					roundsToSummarize.size(), summaryTokens);

			return compressedMessages;
		}
		catch (Exception e) {
			log.warn("Failed to force compress agent memory", e);
			// Return original messages on error
			return new ArrayList<>(messages);
		}
	}

	/**
	 * Check if messages exceed the limit and compress both conversation and agent memory
	 * if needed. This method checks the total token count of all messages (conversation +
	 * agent) and compresses them if they exceed the limit.
	 * @param conversationMemory The conversation memory instance
	 * @param conversationId The conversation ID
	 * @param agentMessages The agent memory messages
	 * @return Compressed agent messages if compression occurred, original messages
	 * otherwise
	 */
	public List<Message> checkAndCompressIfNeeded(ChatMemory conversationMemory, String conversationId,
			List<Message> agentMessages) {
		if (agentMessages == null) {
			agentMessages = new ArrayList<>();
		}

		try {
			// Get conversation messages
			List<Message> extraMessage = new ArrayList<>();
			if (conversationMemory != null && conversationId != null && !conversationId.trim().isEmpty()) {
				List<Message> convMsgs = conversationMemory.get(conversationId);
				if (convMsgs != null) {
					extraMessage = convMsgs;
				}
			}

			// Combine all messages to check total size
			List<Message> allMessages = new ArrayList<>();
			allMessages.addAll(extraMessage);
			allMessages.addAll(agentMessages);

			// Calculate total token count
			int totalTokens = calculateTotalTokens(allMessages);
			int maxTokens = getMaxTokenCount();

			if (totalTokens <= maxTokens) {
				log.debug("Total memory size ({} tokens) is within limit ({} tokens), no compression needed",
						totalTokens, maxTokens);
				return agentMessages;
			}

			log.info(
					"Total memory size ({} tokens) exceeds limit ({} tokens). Force compressing conversation and agent memory...",
					totalTokens, maxTokens);

			// Step 1: Force compress conversation memory first
			if (conversationMemory != null && conversationId != null && !conversationId.trim().isEmpty()
					&& !extraMessage.isEmpty()) {
				try {
					forceCompressConversationMemory(conversationMemory, conversationId);
					log.info("Force compressed conversation memory for conversationId: {}", conversationId);
				}
				catch (Exception e) {
					log.warn("Failed to compress conversation memory for conversationId: {}", conversationId, e);
				}
			}

			// Step 2: Force compress agent memory
			if (!agentMessages.isEmpty()) {
				List<Message> compressedAgentMessages = forceCompressAgentMemory(agentMessages);
				log.info("Force compressed agent memory. Original: {} messages, Compressed: {} messages",
						agentMessages.size(), compressedAgentMessages.size());
				return compressedAgentMessages;
			}

			return agentMessages;
		}
		catch (Exception e) {
			log.warn("Failed to check and compress memory", e);
			return agentMessages;
		}
	}

}

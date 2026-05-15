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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Service for counting tokens in text and messages using JTokkit. Uses cl100k_base
 * encoding (compatible with GPT-4, GPT-3.5-turbo, and similar models).
 */
@Service
public class TokenCountService {

	private static final Logger log = LoggerFactory.getLogger(TokenCountService.class);

	// Thread-safe encoding registry (created once and reused)
	private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
		.getEncoding(EncodingType.CL100K_BASE);

	// Fallback: approximate tokens per character (1 token ≈ 4 characters)
	private static final double FALLBACK_CHARS_PER_TOKEN = 4.0;

	@Autowired(required = false)
	private ObjectMapper objectMapper;

	/**
	 * Count tokens in a text string.
	 * @param text The text to count tokens for
	 * @return Number of tokens
	 */
	public int countTokens(String text) {
		if (text == null || text.trim().isEmpty()) {
			return 0;
		}

		try {
			return ENCODING.countTokens(text);
		}
		catch (Exception e) {
			log.warn("Failed to count tokens using JTokkit, using character estimation: {}", e.getMessage());
			// Fallback to character estimation
			return (int) Math.ceil(text.length() / FALLBACK_CHARS_PER_TOKEN);
		}
	}

	/**
	 * Count tokens in a list of messages. This serializes messages to JSON format
	 * (similar to what would be sent to LLM) for accurate counting.
	 * @param messages List of messages
	 * @return Total token count
	 */
	public int countTokens(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}

		try {
			// Try to serialize messages to JSON for accurate token counting
			// This gives a more accurate count of the actual data that would be sent to
			// LLM
			if (objectMapper != null) {
				String json = objectMapper.writeValueAsString(messages);
				return countTokens(json);
			}
		}
		catch (Exception e) {
			log.debug("Failed to serialize messages to JSON for token counting: {}", e.getMessage());
		}

		// Fallback: count tokens from message text content
		int totalTokens = 0;
		for (Message message : messages) {
			try {
				String text = message.getText();
				if (text != null && !text.trim().isEmpty()) {
					totalTokens += countTokens(text);
				}
			}
			catch (Exception e) {
				log.debug("Failed to extract text from message for token counting: {}", e.getMessage());
			}
		}

		// Add overhead for message structure (role, format, etc.)
		// Approximate: each message adds ~4 tokens for structure
		totalTokens += messages.size() * 4;

		return totalTokens;
	}

	/**
	 * Count tokens in multiple text strings.
	 * @param texts Array of text strings
	 * @return Total token count
	 */
	public int countTokens(String... texts) {
		if (texts == null || texts.length == 0) {
			return 0;
		}

		int totalTokens = 0;
		for (String text : texts) {
			totalTokens += countTokens(text);
		}
		return totalTokens;
	}

}

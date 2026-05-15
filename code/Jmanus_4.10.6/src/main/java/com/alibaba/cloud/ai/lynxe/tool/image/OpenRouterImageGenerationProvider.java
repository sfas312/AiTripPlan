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
package com.alibaba.cloud.ai.lynxe.tool.image;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.alibaba.cloud.ai.lynxe.model.entity.DynamicModelEntity;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Image generation provider for OpenRouter API Uses OpenRouter's chat completions API
 * with modalities parameter
 */
@Component
public class OpenRouterImageGenerationProvider implements ImageGenerationProvider {

	private static final Logger log = LoggerFactory.getLogger(OpenRouterImageGenerationProvider.class);

	private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

	private final ObjectMapper objectMapper;

	public OpenRouterImageGenerationProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider,
			ObjectMapper objectMapper) {
		this.restClientBuilderProvider = restClientBuilderProvider;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(DynamicModelEntity modelEntity, String modelName) {
		if (modelEntity == null || modelName == null) {
			return false;
		}

		String baseUrl = modelEntity.getBaseUrl();
		if (baseUrl == null) {
			return false;
		}

		String lowerBaseUrl = baseUrl.toLowerCase();
		String lowerModelName = modelName.toLowerCase();

		// Step 1: Validate URI - must be openrouter
		boolean isOpenRouter = lowerBaseUrl.contains("openrouter.ai");
		if (!isOpenRouter) {
			return false;
		}

		// Step 2: Validate modelName - check for supported image generation models
		// Google models
		// Nano Banana Pro (Gemini 3 Pro Image Preview)
		boolean isNanoBananaPro = lowerModelName.contains("nano banana pro")
				|| lowerModelName.contains("gemini 3 pro image") || lowerModelName.contains("gemini-3-pro-image")
				|| lowerModelName.contains("gemini-3-pro-image-preview");

		// Gemini 2.5 Flash Image (Nano Banana)
		boolean isNanoBanana = (lowerModelName.contains("nano banana") && !lowerModelName.contains("pro")) // Exclude
																											// "nano
																											// banana
																											// pro"
				|| lowerModelName.contains("gemini 2.5 flash image")
				|| lowerModelName.contains("gemini-2.5-flash-image");

		// Gemini 2.5 Flash Image Preview (Nano Banana)
		boolean isNanoBananaPreview = lowerModelName.contains("gemini 2.5 flash image preview")
				|| lowerModelName.contains("gemini-2.5-flash-image-preview");

		// General Gemini image models
		boolean isGeminiImage = isNanoBananaPro || isNanoBanana || isNanoBananaPreview
				|| (lowerModelName.contains("gemini") && lowerModelName.contains("image"));

		// OpenAI models
		// GPT-5 Image
		boolean isGpt5Image = (lowerModelName.contains("gpt-5") || lowerModelName.contains("gpt 5"))
				&& lowerModelName.contains("image") && !lowerModelName.contains("mini");

		// GPT-5 Image Mini
		boolean isGpt5ImageMini = (lowerModelName.contains("gpt-5") || lowerModelName.contains("gpt 5"))
				&& lowerModelName.contains("image") && lowerModelName.contains("mini");

		// Sourceful models
		// Riverflow V2 Max Preview
		boolean isRiverflow = lowerModelName.contains("riverflow v2 max") || lowerModelName.contains("riverflow-v2-max")
				|| lowerModelName.contains("riverflow-v2-max-preview");

		// Black Forest Labs models
		// FLUX.2 Pro
		boolean isFlux2Pro = lowerModelName.contains("flux.2 pro") || lowerModelName.contains("flux 2 pro")
				|| lowerModelName.contains("flux-2-pro") || lowerModelName.contains("flux.2-pro")
				|| (lowerModelName.contains("flux") && lowerModelName.contains("2") && lowerModelName.contains("pro"));

		// Check if modelName matches any supported image generation model
		if (isGeminiImage || isGpt5Image || isGpt5ImageMini || isRiverflow || isFlux2Pro) {
			log.debug("Detected OpenRouter image generation: model={}, baseUrl={}", modelName, baseUrl);
			return true;
		}

		return false;
	}

	@Override
	public ToolExecuteResult generateImage(ImageGenerationRequest request, DynamicModelEntity modelEntity,
			String modelName, String rootPlanId) {
		try {
			// Validate API key
			String apiKey = modelEntity.getApiKey();
			if (apiKey == null || apiKey.trim().isEmpty()) {
				throw new IllegalArgumentException("API key is required for OpenRouter API");
			}

			// Get base URL from model entity or use default
			// Use normalizeBaseUrlForApiEndpoint to handle /v1 suffix if present
			String rawBaseUrl = modelEntity.getBaseUrl();
			String baseUrl = AbstractBaseTool.normalizeBaseUrl(rawBaseUrl);
			if (baseUrl == null || baseUrl.trim().isEmpty()) {
				baseUrl = "https://openrouter.ai/api";
			}
			else {
				// Normalize for API endpoint (removes /v1 suffix if present)
				baseUrl = AbstractBaseTool.normalizeBaseUrlForApiEndpoint(baseUrl);
				// Ensure baseUrl contains /api for OpenRouter
				String lowerBaseUrl = baseUrl.toLowerCase();
				if (lowerBaseUrl.contains("openrouter.ai") && !lowerBaseUrl.contains("/api")) {
					baseUrl = baseUrl + "/api";
				}
			}

			// Build request body in OpenRouter format
			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("model", modelName);

			// Build messages array
			Map<String, Object> userMessage = new HashMap<>();
			userMessage.put("role", "user");
			userMessage.put("content", request.getPrompt());
			requestBody.put("messages", List.of(userMessage));

			// Add modalities for image generation
			requestBody.put("modalities", List.of("image", "text"));

			// Log the request JSON for debugging
			try {
				String jsonRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
				log.debug("OpenRouter API Request JSON:\n{}", jsonRequest);
			}
			catch (Exception e) {
				log.warn("Failed to serialize request for logging: {}", e.getMessage());
			}

			// Create RestClient with OpenRouter API configuration
			RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
			RestClient restClient;
			if (restClientBuilder != null) {
				restClient = restClientBuilder.clone()
					.baseUrl(baseUrl)
					.defaultHeader("Authorization", "Bearer " + apiKey)
					.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
					.build();
			}
			else {
				restClient = RestClient.builder()
					.baseUrl(baseUrl)
					.defaultHeader("Authorization", "Bearer " + apiKey)
					.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
					.build();
			}

			// Build API endpoint
			String endpoint = "/v1/chat/completions";
			log.debug("Calling OpenRouter API: {}{}", baseUrl, endpoint);

			// Make the API call
			String responseJson = restClient.post().uri(endpoint).body(requestBody).retrieve().body(String.class);

			if (responseJson == null || responseJson.trim().isEmpty()) {
				return new ToolExecuteResult("No response received from OpenRouter API");
			}

			log.debug("OpenRouter API Response received (length: {} characters)", responseJson.length());

			// Extract images from OpenRouter response format
			List<String> imageUrls = extractImagesFromResponse(responseJson);

			if (imageUrls.isEmpty()) {
				// Check if there's an error in the response
				try {
					@SuppressWarnings("unchecked")
					Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
					Object error = responseMap.get("error");
					if (error != null) {
						String errorMsg = error.toString();
						log.error("OpenRouter API returned error: {}", errorMsg);
						return new ToolExecuteResult("OpenRouter API error: " + errorMsg);
					}
				}
				catch (Exception e) {
					log.debug("Failed to parse error from response: {}", e.getMessage());
				}
				return new ToolExecuteResult("No images found in OpenRouter API response");
			}

			// Return result as JSON string
			Map<String, Object> resultMap = new HashMap<>();
			resultMap.put("images", imageUrls);
			resultMap.put("count", imageUrls.size());
			resultMap.put("prompt", request.getPrompt());
			if (modelName != null && !modelName.trim().isEmpty()) {
				resultMap.put("model", modelName);
			}
			if (request.getSize() != null) {
				resultMap.put("size", request.getSize());
			}
			if (request.getQuality() != null) {
				resultMap.put("quality", request.getQuality());
			}
			resultMap.put("method", "openrouter-chat");

			String resultJson = objectMapper.writeValueAsString(resultMap);
			log.info("OpenRouter image generation successful: {} image(s) generated", imageUrls.size());
			return new ToolExecuteResult(resultJson);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			log.error("JSON processing error in OpenRouter image generation: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to serialize image generation result", e);
		}
		catch (Exception e) {
			log.error("Error in OpenRouter image generation: {}", e.getMessage(), e);
			throw e; // Re-throw to be handled by run() method
		}
	}

	/**
	 * Extract images from OpenRouter API response OpenRouter returns images in
	 * choices[].message.images[].image_url.url format
	 * @param responseJson JSON response string from OpenRouter API
	 * @return List of base64 data URLs
	 */
	@SuppressWarnings("unchecked")
	private List<String> extractImagesFromResponse(String responseJson) {
		List<String> imageUrls = new ArrayList<>();

		try {
			Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
			List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");

			if (choices == null || choices.isEmpty()) {
				log.debug("No choices found in OpenRouter API response");
				return imageUrls;
			}

			for (Map<String, Object> choice : choices) {
				Map<String, Object> message = (Map<String, Object>) choice.get("message");
				if (message == null) {
					continue;
				}

				List<Map<String, Object>> images = (List<Map<String, Object>>) message.get("images");
				if (images == null || images.isEmpty()) {
					continue;
				}

				for (Map<String, Object> image : images) {
					Map<String, Object> imageUrl = (Map<String, Object>) image.get("image_url");
					if (imageUrl != null) {
						String url = (String) imageUrl.get("url");
						if (url != null && !url.trim().isEmpty()) {
							imageUrls.add(url);
							log.debug("Extracted image from OpenRouter response (url length: {})", url.length());
						}
					}
				}
			}
		}
		catch (Exception e) {
			log.error("Error extracting images from OpenRouter API response: {}", e.getMessage(), e);
		}

		return imageUrls;
	}

}

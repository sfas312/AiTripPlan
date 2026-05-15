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

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.model.entity.DynamicModelEntity;
import com.alibaba.cloud.ai.lynxe.model.repository.DynamicModelRepository;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ImageGenerationTool extends AbstractBaseTool<ImageGenerationRequest> {

	private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);

	private final DynamicModelRepository dynamicModelRepository;

	private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	private final LynxeProperties lynxeProperties;

	private final List<ImageGenerationProvider> imageGenerationProviders;

	public ImageGenerationTool(DynamicModelRepository dynamicModelRepository,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider, ObjectMapper objectMapper,
			ToolI18nService toolI18nService, LynxeProperties lynxeProperties,
			List<ImageGenerationProvider> imageGenerationProviders) {
		this.dynamicModelRepository = dynamicModelRepository;
		this.restClientBuilderProvider = restClientBuilderProvider;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
		this.lynxeProperties = lynxeProperties;
		this.imageGenerationProviders = imageGenerationProviders != null ? imageGenerationProviders : List.of();
	}

	@Override
	public String getServiceGroup() {
		return "image";
	}

	@Override
	public String getName() {
		return "image-generate";
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("image-generate-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("image-generate-tool");
	}

	@Override
	public Class<ImageGenerationRequest> getInputType() {
		return ImageGenerationRequest.class;
	}

	@Override
	public ToolExecuteResult run(ImageGenerationRequest request) {
		log.info("ImageGenerationTool request received: prompt={}, model={}, size={}, quality={}, n={}",
				request != null ? request.getPrompt() : null, request != null ? request.getModel() : null,
				request != null ? request.getSize() : null, request != null ? request.getQuality() : null,
				request != null ? request.getN() : null);

		DynamicModelEntity modelEntity = null;
		try {
			// Validate prompt
			if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
				return new ToolExecuteResult("Prompt is required for image generation");
			}

			// Use configured model name if model in request is null
			String modelName = request.getModel();
			if (modelName == null || modelName.trim().isEmpty()) {
				modelName = getConfiguredModelName();
				log.debug("Model not specified in request, using configured model name: {}", modelName);
			}

			// Get model configuration
			modelEntity = getModelEntity(modelName);
			if (modelEntity == null) {
				return new ToolExecuteResult("Model configuration not found. Please configure a model first.");
			}

			// Try to find a provider that supports this model
			ImageGenerationProvider provider = null;
			for (ImageGenerationProvider p : imageGenerationProviders) {
				if (p.supports(modelEntity, modelName)) {
					provider = p;
					log.info("Found image generation provider for model: {} - {}", modelName,
							p.getClass().getSimpleName());
					break;
				}
			}

			// If provider found, use it
			if (provider != null) {
				return provider.generateImage(request, modelEntity, modelName, getRootPlanId());
			}

			// Fallback to Google Direct API if supported
			if (isGoogleDirectApiGeneration(modelEntity)) {
				log.info("Using direct Google API image generation for model: {}", modelName);
				return generateImageViaGoogleDirectApi(request, modelEntity, modelName);
			}

			// No provider found
			log.warn("Image generation not supported for this model configuration. Model: {}, BaseUrl: {}", modelName,
					modelEntity.getBaseUrl());
			return new ToolExecuteResult(
					"Image generation is not supported for this model. Please configure a supported image generation provider.");
		}
		catch (IllegalArgumentException e) {
			log.error("Invalid argument in image generation: {}", e.getMessage(), e);
			return new ToolExecuteResult("Image generation failed: Invalid argument - " + e.getMessage());
		}
		catch (RuntimeException e) {
			log.error("Runtime error during image generation: {}", e.getMessage(), e);
			// Enhanced error message for HTML response errors
			String errorMessage = buildEnhancedErrorMessage((Exception) e, modelEntity);
			return new ToolExecuteResult(errorMessage);
		}
		catch (Exception e) {
			log.error("Unexpected error during image generation: {}", e.getMessage(), e);
			// Enhanced error message for HTML response errors
			String errorMessage = buildEnhancedErrorMessage(e, modelEntity);
			return new ToolExecuteResult(errorMessage);
		}
	}

	/**
	 * Generate image using direct Google Generative Language API
	 * @param request Image generation request
	 * @param modelEntity Model entity configuration
	 * @param modelName Model name
	 * @return Tool execution result with image URLs (base64 data URLs)
	 */
	private ToolExecuteResult generateImageViaGoogleDirectApi(ImageGenerationRequest request,
			DynamicModelEntity modelEntity, String modelName) {
		try {
			// Validate API key
			String apiKey = modelEntity.getApiKey();
			if (apiKey == null || apiKey.trim().isEmpty()) {
				throw new IllegalArgumentException("API key is required for Google API");
			}

			// Get base URL from model entity or use default
			String baseUrl = AbstractBaseTool.normalizeBaseUrl(modelEntity.getBaseUrl());
			if (baseUrl == null || baseUrl.trim().isEmpty()) {
				baseUrl = "https://generativelanguage.googleapis.com";
			}

			// Map model name to Google API format
			String googleModelName = mapToGoogleModelName(modelName);

			// Build request body in Google's format
			Map<String, Object> requestBody = new HashMap<>();
			Map<String, Object> contents = Map.of("role", "user", "parts",
					List.of(Map.of("text", request.getPrompt())));
			requestBody.put("contents", List.of(contents));

			// Add generation config with response modalities
			Map<String, Object> generationConfig = new HashMap<>();
			generationConfig.put("responseModalities", List.of("TEXT", "IMAGE"));
			requestBody.put("generationConfig", generationConfig);

			// Log the request JSON for debugging
			try {
				String jsonRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
				log.debug("Google API Request JSON:\n{}", jsonRequest);
			}
			catch (Exception e) {
				log.warn("Failed to serialize request for logging: {}", e.getMessage());
			}

			// Create RestClient with Google API configuration
			RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder);
			RestClient restClient;
			if (restClientBuilder != null) {
				restClient = restClientBuilder.clone()
					.baseUrl(baseUrl)
					.defaultHeader("x-goog-api-key", apiKey)
					.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
					.build();
			}
			else {
				restClient = RestClient.builder()
					.baseUrl(baseUrl)
					.defaultHeader("x-goog-api-key", apiKey)
					.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
					.build();
			}

			// Build API endpoint
			String endpoint = "/v1beta/models/" + googleModelName + ":generateContent";
			log.debug("Calling Google API: {}{}", baseUrl, endpoint);

			// Make the API call
			String responseJson = restClient.post().uri(endpoint).body(requestBody).retrieve().body(String.class);

			if (responseJson == null || responseJson.trim().isEmpty()) {
				return new ToolExecuteResult("No response received from Google API");
			}

			log.debug("Google API Response received (length: {} characters)", responseJson.length());

			// Extract images from response
			List<String> imageUrls = extractImagesFromGoogleResponse(responseJson);

			if (imageUrls.isEmpty()) {
				// Check if there's an error in the response
				try {
					@SuppressWarnings("unchecked")
					Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
					Object error = responseMap.get("error");
					if (error != null) {
						String errorMsg = error.toString();
						log.error("Google API returned error: {}", errorMsg);
						return new ToolExecuteResult("Google API error: " + errorMsg);
					}
				}
				catch (Exception e) {
					log.debug("Failed to parse error from response: {}", e.getMessage());
				}
				return new ToolExecuteResult("No images found in Google API response");
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
			resultMap.put("method", "google-direct-api");

			String resultJson = objectMapper.writeValueAsString(resultMap);
			log.info("Google direct API image generation successful: {} image(s) generated", imageUrls.size());
			return new ToolExecuteResult(resultJson);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			log.error("JSON processing error in Google direct API image generation: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to serialize image generation result", e);
		}
		catch (Exception e) {
			log.error("Error in Google direct API image generation: {}", e.getMessage(), e);
			throw e; // Re-throw to be handled by run() method
		}
	}

	/**
	 * Extract images from Google API response Google API returns images in
	 * candidates[].content.parts[].inlineData format
	 * @param responseJson JSON response string from Google API
	 * @return List of base64 data URLs
	 */
	@SuppressWarnings("unchecked")
	private List<String> extractImagesFromGoogleResponse(String responseJson) {
		List<String> imageUrls = new ArrayList<>();

		try {
			Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
			List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");

			if (candidates == null || candidates.isEmpty()) {
				log.debug("No candidates found in Google API response");
				return imageUrls;
			}

			for (Map<String, Object> candidate : candidates) {
				Map<String, Object> content = (Map<String, Object>) candidate.get("content");
				if (content == null) {
					continue;
				}

				List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
				if (parts == null || parts.isEmpty()) {
					continue;
				}

				for (Map<String, Object> part : parts) {
					Map<String, Object> inlineData = (Map<String, Object>) part.get("inlineData");
					if (inlineData != null) {
						String mimeType = (String) inlineData.get("mimeType");
						String data = (String) inlineData.get("data");

						if (data != null && !data.trim().isEmpty()) {
							// Construct data URL
							String dataUrl = "data:" + (mimeType != null ? mimeType : "image/png") + ";base64," + data;
							imageUrls.add(dataUrl);
							log.debug("Extracted image from Google API response (mimeType: {}, data length: {})",
									mimeType, data.length());
						}
					}
				}
			}
		}
		catch (Exception e) {
			log.error("Error extracting images from Google API response: {}", e.getMessage(), e);
		}

		return imageUrls;
	}

	/**
	 * Get model entity from repository, similar to how LlmService does it
	 * @param modelName Optional model name, uses default if null
	 * @return DynamicModelEntity or null if not found
	 */
	private DynamicModelEntity getModelEntity(String modelName) {
		try {
			if (modelName != null && !modelName.trim().isEmpty()) {
				// Try to find by model name
				List<DynamicModelEntity> models = dynamicModelRepository.findAll();
				for (DynamicModelEntity model : models) {
					if (modelName.equals(model.getModelName())) {
						return model;
					}
				}
				log.warn("Model with name '{}' not found, using default model", modelName);
			}

			// Use default model
			DynamicModelEntity defaultModel = dynamicModelRepository.findByIsDefaultTrue();
			if (defaultModel != null) {
				return defaultModel;
			}

			// Fallback to first available model
			List<DynamicModelEntity> availableModels = dynamicModelRepository.findAll();
			if (!availableModels.isEmpty()) {
				log.info("Using first available model: {}", availableModels.get(0).getModelName());
				return availableModels.get(0);
			}

			log.error("No model configuration found in repository");
			return null;
		}
		catch (Exception e) {
			log.error("Error getting model entity from repository", e);
			return null;
		}
	}

	/**
	 * Get configured model name from LynxeProperties
	 * @return configured model name or null if not configured
	 */
	private String getConfiguredModelName() {
		if (lynxeProperties != null) {
			String configuredModelName = lynxeProperties.getImageGenerationModelName();
			if (configuredModelName != null && !configuredModelName.trim().isEmpty()) {
				return configuredModelName;
			}
		}
		return null;
	}

	/**
	 * Detect if the model should use direct Google API generation
	 * @param modelEntity The model entity to check
	 * @return true if Google direct API should be used, false otherwise
	 */
	private boolean isGoogleDirectApiGeneration(DynamicModelEntity modelEntity) {
		if (modelEntity == null) {
			return false;
		}

		String baseUrl = modelEntity.getBaseUrl();
		String modelName = modelEntity.getModelName();

		// Check if base URL contains generativelanguage.googleapis.com
		if (baseUrl != null && baseUrl.toLowerCase().contains("generativelanguage.googleapis.com")) {
			log.debug("Detected Google Generative Language API base URL: {}", baseUrl);
			return true;
		}

		// Check if base URL contains googleapis.com and model name contains gemini
		if (baseUrl != null && baseUrl.toLowerCase().contains("googleapis.com")) {
			if (modelName != null && modelName.toLowerCase().contains("gemini")) {
				log.debug("Detected Google API with Gemini model: {}", modelName);
				return true;
			}
		}

		// Check if model name matches Google Gemini patterns
		if (modelName != null) {
			String lowerModelName = modelName.toLowerCase();
			// Check for Google Gemini image models (e.g., gemini-3-pro-image-preview,
			// gemini-2.5-flash-image-preview)
			if ((lowerModelName.contains("gemini") && lowerModelName.contains("image"))
					|| lowerModelName.startsWith("gemini-") && lowerModelName.contains("-image-")) {
				log.debug("Detected Google Gemini image model: {}", modelName);
				return true;
			}
		}

		return false;
	}

	/**
	 * Map model name to Google API format Converts names like
	 * "google/gemini-3-pro-image-preview" to "gemini-3-pro-image-preview"
	 * @param modelName Original model name
	 * @return Model name suitable for Google API endpoint
	 */
	private String mapToGoogleModelName(String modelName) {
		if (modelName == null || modelName.trim().isEmpty()) {
			return modelName;
		}

		String mapped = modelName.trim();
		// Remove "google/" prefix if present
		if (mapped.startsWith("google/")) {
			mapped = mapped.substring(7);
		}
		// Remove any other prefixes that might be present
		if (mapped.contains("/")) {
			String[] parts = mapped.split("/");
			mapped = parts[parts.length - 1]; // Take the last part
		}

		log.debug("Mapped model name from '{}' to '{}'", modelName, mapped);
		return mapped;
	}

	/**
	 * Build enhanced error message for image generation failures Provides detailed
	 * information about HTML response errors and OpenRouter-specific guidance
	 * @param e The exception that occurred
	 * @param modelEntity The model entity used for the request (can be null)
	 * @return Enhanced error message
	 */
	private String buildEnhancedErrorMessage(Exception e, DynamicModelEntity modelEntity) {
		String errorMessage = e.getMessage();
		// Check if using provider-based generation
		boolean isProviderBased = false;
		if (modelEntity != null) {
			for (ImageGenerationProvider provider : imageGenerationProviders) {
				if (provider.supports(modelEntity, modelEntity.getModelName())) {
					isProviderBased = true;
					break;
				}
			}
		}
		boolean isGoogleDirect = modelEntity != null && isGoogleDirectApiGeneration(modelEntity);
		boolean isOpenRouter = modelEntity != null && !isGoogleDirect && modelEntity.getBaseUrl() != null
				&& modelEntity.getBaseUrl().toLowerCase().contains("openrouter.ai");

		// Check for HTML response errors
		if (errorMessage != null && errorMessage.contains("text/html")) {
			StringBuilder msg = new StringBuilder();
			msg.append("\n╔════════════════════════════════════════════════════════════════╗\n");
			msg.append("║  Image API returned HTML instead of JSON - Configuration Issue  ║\n");
			msg.append("╚════════════════════════════════════════════════════════════════╝\n\n");

			if (modelEntity != null) {
				msg.append("Model: ").append(modelEntity.getModelName()).append("\n");
				msg.append("Base URL: ").append(modelEntity.getBaseUrl()).append("\n");
				String apiKeyPreview = modelEntity.getApiKey() != null && modelEntity.getApiKey().length() > 7
						? modelEntity.getApiKey().substring(0, 7) + "..." : "(not set)";
				msg.append("API Key: ").append(apiKeyPreview).append("\n");
				String method = isGoogleDirect ? "Direct Google API"
						: (isProviderBased ? "Provider-based" : "API-based");
				msg.append("Generation Method: ").append(method).append("\n\n");
			}

			msg.append("❌ HTML RESPONSE ERROR\n\n");
			msg.append("The image API returned HTML instead of JSON. This typically indicates:\n\n");
			msg.append("Possible causes:\n");
			msg.append("  1. Invalid API key - Check your model configuration\n");
			msg.append("  2. API key not properly loaded from database\n");
			msg.append("  3. Incorrect base URL configuration\n");
			msg.append("  4. Network/proxy issues redirecting to error page\n");

			if (isGoogleDirect) {
				msg.append("\n📌 Google Direct API Notes:\n");
				msg.append("  • Google API uses /v1beta/models/{model}:generateContent endpoint\n");
				msg.append("  • Uses x-goog-api-key header instead of Authorization Bearer\n");
				msg.append("  • Verify your Google API key at https://makersuite.google.com/app/apikey\n");
				msg.append("  • Ensure the model name is correct (e.g., gemini-3-pro-image-preview)\n");
				msg.append("  • Check that the API key has access to Generative Language API\n");
			}
			else if (isOpenRouter) {
				msg.append("\n📌 OpenRouter-Specific Notes:\n");
				msg.append("  • OpenRouter uses chat completions with modalities parameter\n");
				msg.append("  • Ensure your model supports image generation (check output_modalities)\n");
				msg.append("  • Verify the model name is correct (e.g., google/gemini-2.5-flash-image-preview)\n");
				msg.append("  • Check OpenRouter API key at https://openrouter.ai/keys\n");
			}
			else {
				msg.append("\n📌 Traditional API Notes:\n");
				msg.append("  • Traditional APIs use /v1/images/generations endpoint\n");
				msg.append("  • Verify your API key at https://platform.openai.com/api-keys\n");
				msg.append("  • If using OpenRouter models, they may require chat-based generation\n");
			}

			msg.append("\nHow to fix:\n");
			if (isGoogleDirect) {
				msg.append("  • Verify your Google API key at https://makersuite.google.com/app/apikey\n");
				msg.append("  • Check the API key has Generative Language API enabled\n");
				msg.append("  • Ensure baseUrl is https://generativelanguage.googleapis.com\n");
				msg.append("  • Verify the model name is correct for Google API\n");
			}
			else if (isOpenRouter) {
				msg.append("  • Verify your OpenRouter API key at https://openrouter.ai/keys\n");
				msg.append("  • Check the model name supports image generation\n");
				msg.append("  • Ensure baseUrl is https://openrouter.ai/api\n");
			}
			else {
				msg.append("  • Verify your API key at https://platform.openai.com/api-keys\n");
				msg.append("  • Check the API key in your model configuration\n");
				msg.append("  • Ensure the baseUrl is correct (e.g., https://api.openai.com)\n");
			}
			msg.append("  • Check network connectivity and proxy settings\n\n");
			msg.append("Error Details: ").append(errorMessage).append("\n");

			return msg.toString();
		}

		// Check for modality-related errors
		if (errorMessage != null && (errorMessage.contains("modalities") || errorMessage.contains("modality"))) {
			StringBuilder msg = new StringBuilder();
			msg.append("\n╔════════════════════════════════════════════════════════════════╗\n");
			msg.append("║  Modality Configuration Error - OpenRouter Image Generation     ║\n");
			msg.append("╚════════════════════════════════════════════════════════════════╝\n\n");

			if (modelEntity != null) {
				msg.append("Model: ").append(modelEntity.getModelName()).append("\n");
				msg.append("Base URL: ").append(modelEntity.getBaseUrl()).append("\n\n");
			}

			msg.append("❌ MODALITY ERROR\n\n");
			msg.append("The model may not support image generation or the modalities parameter is incorrect.\n\n");
			msg.append("Possible causes:\n");
			msg.append("  1. Model does not support image generation (check output_modalities)\n");
			msg.append("  2. Modalities parameter not properly set\n");
			msg.append("  3. Model name incorrect for image generation\n\n");
			msg.append("How to fix:\n");
			msg.append("  • Verify the model supports image generation at https://openrouter.ai/models\n");
			msg.append("  • Check that the model has 'image' in its output_modalities\n");
			msg.append("  • Use a model specifically designed for image generation\n");
			msg.append("  • Examples: google/gemini-2.5-flash-image-preview, black-forest-labs/flux.2-pro\n\n");
			msg.append("Error Details: ").append(errorMessage).append("\n");

			return msg.toString();
		}

		// For other errors, provide standard error message with context
		StringBuilder msg = new StringBuilder();
		msg.append("Image generation failed: ").append(errorMessage);
		if (isGoogleDirect) {
			msg.append("\n\nNote: Using direct Google API generation.");
		}
		else if (isProviderBased) {
			msg.append("\n\nNote: Using provider-based image generation.");
		}
		Throwable cause = e.getCause();
		if (cause != null && cause.getMessage() != null) {
			msg.append("\nCause: ").append(cause.getMessage());
		}
		return msg.toString();
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up image generation resources for plan: {}", planId);
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String stateString;
		try {
			StringBuilder stateBuilder = new StringBuilder();
			stateBuilder.append("\n=== Image Generation Tool Current State ===\n");
			stateBuilder.append("Tool is ready to generate images from text prompts.\n");
			stateBuilder.append("Default size: 1024x1024\n");
			stateBuilder.append("Default quality: standard\n");
			stateBuilder.append("Supported sizes: 256x256, 512x512, 1024x1024, 1792x1024, 1024x1792\n");
			stateBuilder.append("Supported quality: standard, hd\n");
			stateBuilder.append("Supported number of images: 1-10\n");
			stateBuilder.append("\n=== End Image Generation Tool State ===\n");
			stateString = stateBuilder.toString();
		}
		catch (Exception e) {
			log.error("Failed to get image generation tool state", e);
			stateString = String.format("Image generation tool state error: %s", e.getMessage());
		}
		return new ToolStateInfo(null, stateString);
	}

}

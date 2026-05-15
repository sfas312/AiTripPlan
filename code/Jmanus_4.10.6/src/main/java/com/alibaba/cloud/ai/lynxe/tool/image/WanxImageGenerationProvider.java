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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Image generation provider for Alibaba Wanx API Uses DashScope multimodal generation API
 * for text-to-image models Uses DashScope image generation API for image editing models
 */
@Component
public class WanxImageGenerationProvider implements ImageGenerationProvider {

	private static final Logger log = LoggerFactory.getLogger(WanxImageGenerationProvider.class);

	private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;

	private final ObjectMapper objectMapper;

	private final UnifiedDirectoryManager directoryManager;

	public WanxImageGenerationProvider(ObjectProvider<RestClient.Builder> restClientBuilderProvider,
			ObjectMapper objectMapper, UnifiedDirectoryManager directoryManager) {
		this.restClientBuilderProvider = restClientBuilderProvider;
		this.objectMapper = objectMapper;
		this.directoryManager = directoryManager;
	}

	/**
	 * Check if this provider supports the given model
	 *
	 * Supported models include:
	 *
	 * Wanx Text-to-Image V2: - wan2.6-t2i, wan2.5-t2i-preview, wan2.2-t2i-plus,
	 * wan2.2-t2i-flash - wanx2.1-t2i-plus, wanx2.1-t2i-turbo, wanx2.0-t2i-turbo Endpoint:
	 * /api/v1/services/aigc/multimodal-generation/generation (synchronous)
	 *
	 * Wanx Text-to-Image V1: - wanx-v1 Endpoint:
	 * /api/v1/services/aigc/multimodal-generation/generation (synchronous)
	 *
	 * Wanx Image Generation and Editing 2.6: - wan2.6-image Endpoint:
	 * /api/v1/services/aigc/image-generation/generation (asynchronous)
	 *
	 * Wanx Universal Image Editing: - wan2.5-i2i-preview, wanx2.1-imageedit
	 *
	 * Other Wanx Models: - wanx-sketch-to-image-lite, wanx-x-painting,
	 * wanx-style-repaint-v1 - wanx-background-generation-v2, image-out-painting -
	 * wanx-virtualmodel, virtualmodel-v2, shoemodel-v1 - wanx-poster-generation-v1
	 */
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

		// Step 1: Validate URI - must be dashscope
		boolean isDashScope = lowerBaseUrl.contains("dashscope.aliyuncs.com");
		if (!isDashScope) {
			return false;
		}

		// Step 2: Validate modelName - check for supported Wanx models

		// Wanx Text-to-Image Models V2
		boolean isWan26T2i = lowerModelName.contains("wan2.6-t2i");
		boolean isWan25T2iPreview = lowerModelName.contains("wan2.5-t2i-preview");
		boolean isWan22T2iPlus = lowerModelName.contains("wan2.2-t2i-plus");
		boolean isWan22T2iFlash = lowerModelName.contains("wan2.2-t2i-flash");
		boolean isWanx21T2iPlus = lowerModelName.contains("wanx2.1-t2i-plus");
		boolean isWanx21T2iTurbo = lowerModelName.contains("wanx2.1-t2i-turbo");
		boolean isWanx20T2iTurbo = lowerModelName.contains("wanx2.0-t2i-turbo");

		// Wanx Text-to-Image Models V1
		boolean isWanxV1 = lowerModelName.equals("wanx-v1") || lowerModelName.contains("wanx-v1");

		// Wanx Image Generation and Editing 2.6
		boolean isWan26Image = lowerModelName.contains("wan2.6-image");

		// Wanx Universal Image Editing 2.5
		boolean isWan25I2iPreview = lowerModelName.contains("wan2.5-i2i-preview");

		// Wanx Universal Image Editing 2.1
		boolean isWanx21ImageEdit = lowerModelName.contains("wanx2.1-imageedit");

		// Wanx Sketch to Image
		boolean isWanxSketch = lowerModelName.contains("wanx-sketch-to-image");

		// Wanx Local Repainting
		boolean isWanxPainting = lowerModelName.contains("wanx-x-painting");

		// Wanx Portrait Style Repainting
		boolean isWanxStyleRepaint = lowerModelName.contains("wanx-style-repaint");

		// Wanx Background Generation
		boolean isWanxBackground = lowerModelName.contains("wanx-background-generation");

		// Image Expansion
		boolean isImageOutPainting = lowerModelName.contains("image-out-painting");

		// Virtual Model
		boolean isWanxVirtualModel = lowerModelName.contains("wanx-virtualmodel")
				|| lowerModelName.contains("virtualmodel");

		// Shoe Model
		boolean isShoeModel = lowerModelName.contains("shoemodel");

		// Poster Generation
		boolean isWanxPoster = lowerModelName.contains("wanx-poster-generation");

		// General Wanx pattern (catch-all for other wanx models)
		boolean isWanx = lowerModelName.contains("wanx") || lowerModelName.contains("wan2.")
				|| lowerModelName.contains("wanx2.");

		// Check if model matches any supported pattern
		if (isWan26T2i || isWan25T2iPreview || isWan22T2iPlus || isWan22T2iFlash || isWanx21T2iPlus || isWanx21T2iTurbo
				|| isWanx20T2iTurbo || isWanxV1 || isWan26Image || isWan25I2iPreview || isWanx21ImageEdit
				|| isWanxSketch || isWanxPainting || isWanxStyleRepaint || isWanxBackground || isImageOutPainting
				|| isWanxVirtualModel || isShoeModel || isWanxPoster || isWanx) {
			log.debug("Detected Wanx image generation: model={}, baseUrl={}", modelName, baseUrl);
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
				throw new IllegalArgumentException("API key is required for Wanx API");
			}

			// Determine full endpoint URL based on region
			// Beijing region:
			// https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
			// Singapore region:
			// https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
			String rawBaseUrl = modelEntity.getBaseUrl();
			String fullEndpointUrl;

			if (rawBaseUrl != null && !rawBaseUrl.trim().isEmpty()) {
				String normalizedBaseUrl = AbstractBaseTool.normalizeBaseUrl(rawBaseUrl);
				// Check if it's Singapore region
				if (normalizedBaseUrl != null && normalizedBaseUrl.toLowerCase().contains("dashscope-intl")) {
					fullEndpointUrl = "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
				}
				else {
					// Default to Beijing region
					fullEndpointUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
				}
			}
			else {
				// Default to Beijing region
				fullEndpointUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
			}

			// Build request body in Wanx format
			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("model", modelName);

			// Build input.messages array
			Map<String, Object> input = new HashMap<>();
			Map<String, Object> userMessage = new HashMap<>();
			userMessage.put("role", "user");

			// Build content array with text
			Map<String, Object> textContent = new HashMap<>();
			textContent.put("text", request.getPrompt());
			userMessage.put("content", List.of(textContent));
			input.put("messages", List.of(userMessage));
			requestBody.put("input", input);

			// Build parameters
			Map<String, Object> parameters = new HashMap<>();
			parameters.put("negative_prompt", "");
			parameters.put("prompt_extend", true);
			parameters.put("watermark", false);

			// Parse and validate size from request (e.g., "1024x1024" -> "1024*1024")
			// Default size: 1280*1280 for wan2.6-t2i and wan2.5-t2i-preview, 1024*1024
			// for older models
			String size = validateAndAdjustSize(request.getSize(), modelName);
			parameters.put("size", size);

			// Add number of images to generate (n parameter, range 1-4 for Wanx models)
			// Default to 1 if not specified
			int n = 1; // Default value
			if (request.getN() != null && request.getN() >= 1 && request.getN() <= 4) {
				n = request.getN();
			}
			parameters.put("n", n);

			requestBody.put("parameters", parameters);

			// Log the request JSON for debugging
			try {
				String jsonRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
				log.debug("Wanx API Request JSON:\n{}", jsonRequest);
			}
			catch (Exception e) {
				log.warn("Failed to serialize request for logging: {}", e.getMessage());
			}

			// Extract base URL from full endpoint URL for RestClient
			java.net.URI endpointUri = java.net.URI.create(fullEndpointUrl);
			String baseUrl = endpointUri.getScheme() + "://" + endpointUri.getHost();
			String endpointPath = endpointUri.getPath();

			// Create RestClient with Wanx API configuration
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

			log.debug("Calling Wanx API: {}", fullEndpointUrl);

			// Make the API call
			String responseJson = restClient.post().uri(endpointPath).body(requestBody).retrieve().body(String.class);

			if (responseJson == null || responseJson.trim().isEmpty()) {
				return new ToolExecuteResult("No response received from Wanx API");
			}

			log.debug("Wanx API Response received (length: {} characters)", responseJson.length());

			// Extract images from Wanx response format
			List<String> imageUrls = extractImagesFromResponse(responseJson);

			// Download images to local folder if rootPlanId is provided
			if (rootPlanId != null && !rootPlanId.trim().isEmpty() && !imageUrls.isEmpty()) {
				try {
					imageUrls = downloadImagesToLocalFolder(imageUrls, rootPlanId);
					log.info("Downloaded {} image(s) to local folder for rootPlanId: {}", imageUrls.size(), rootPlanId);
				}
				catch (Exception e) {
					log.error("Failed to download images to local folder: {}", e.getMessage(), e);
					// Continue with remote URLs if download fails
				}
			}

			if (imageUrls.isEmpty()) {
				// Check if there's an error in the response
				try {
					@SuppressWarnings("unchecked")
					Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);

					// Check for error code and message at root level
					Object code = responseMap.get("code");
					Object message = responseMap.get("message");
					if (code != null || message != null) {
						String errorMsg = message != null ? message.toString()
								: (code != null ? code.toString() : "Unknown error");
						log.error("Wanx API returned error: code={}, message={}", code, message);
						return new ToolExecuteResult("Wanx API error: " + errorMsg);
					}

					// Also check for error object (legacy format)
					Object error = responseMap.get("error");
					if (error != null) {
						String errorMsg = error.toString();
						log.error("Wanx API returned error: {}", errorMsg);
						return new ToolExecuteResult("Wanx API error: " + errorMsg);
					}
				}
				catch (Exception e) {
					log.debug("Failed to parse error from response: {}", e.getMessage());
				}
				return new ToolExecuteResult("No images found in Wanx API response");
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
			resultMap.put("method", "wanx");

			String resultJson = objectMapper.writeValueAsString(resultMap);
			log.info("Wanx image generation successful: {} image(s) generated", imageUrls.size());
			return new ToolExecuteResult(resultJson);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			log.error("JSON processing error in Wanx image generation: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to serialize image generation result", e);
		}
		catch (Exception e) {
			log.error("Error in Wanx image generation: {}", e.getMessage(), e);
			throw e; // Re-throw to be handled by run() method
		}
	}

	/**
	 * Extract images from Wanx API response Wanx returns images in
	 * output.choices[].message.content[].image format
	 * @param responseJson JSON response string from Wanx API
	 * @return List of image URLs
	 */
	@SuppressWarnings("unchecked")
	private List<String> extractImagesFromResponse(String responseJson) {
		List<String> imageUrls = new ArrayList<>();

		try {
			Map<String, Object> responseMap = objectMapper.readValue(responseJson, Map.class);
			Map<String, Object> output = (Map<String, Object>) responseMap.get("output");

			if (output == null) {
				log.debug("No output found in Wanx API response");
				return imageUrls;
			}

			List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
			if (choices == null || choices.isEmpty()) {
				log.debug("No choices found in Wanx API response");
				return imageUrls;
			}

			for (Map<String, Object> choice : choices) {
				Map<String, Object> message = (Map<String, Object>) choice.get("message");
				if (message == null) {
					continue;
				}

				List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
				if (content == null || content.isEmpty()) {
					continue;
				}

				for (Map<String, Object> contentItem : content) {
					String image = (String) contentItem.get("image");
					if (image != null && !image.trim().isEmpty()) {
						imageUrls.add(image);
						log.debug("Extracted image from Wanx response: {}", image);
					}
				}
			}
		}
		catch (Exception e) {
			log.error("Error extracting images from Wanx API response: {}", e.getMessage(), e);
		}

		return imageUrls;
	}

	/**
	 * Download images from remote URLs to local root plan folder
	 * @param remoteUrls List of remote image URLs
	 * @param rootPlanId Root plan ID for the folder path
	 * @return List of local file paths
	 * @throws IOException if download or file operations fail
	 */
	private List<String> downloadImagesToLocalFolder(List<String> remoteUrls, String rootPlanId) throws IOException {
		List<String> localPaths = new ArrayList<>();

		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			log.warn("rootPlanId is null or empty, cannot download images to local folder");
			return remoteUrls;
		}

		// Get root plan directory
		Path rootPlanDir = directoryManager.getRootPlanDirectory(rootPlanId);

		// Create images subdirectory if it doesn't exist
		Path imagesDir = rootPlanDir.resolve("images");
		Files.createDirectories(imagesDir);

		// Generate timestamp and UUID for unique filenames
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
		String uniqueId = UUID.randomUUID().toString().substring(0, 8);

		// Download each image using HttpURLConnection to preserve OSS signed URL exactly
		for (int i = 0; i < remoteUrls.size(); i++) {
			String remoteUrl = remoteUrls.get(i);
			try {
				// Generate unique filename: wanx_image_20250121_143022_a1b2c3d4_1.png
				String filename = String.format("wanx_image_%s_%s_%d.png", timestamp, uniqueId, i + 1);
				Path localFile = imagesDir.resolve(filename);

				// Download image using HttpURLConnection to preserve exact URL (important
				// for OSS signed URLs)
				URI uri = URI.create(remoteUrl);
				HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
				connection.setRequestMethod("GET");
				connection.setConnectTimeout(30000); // 30 seconds
				connection.setReadTimeout(60000); // 60 seconds
				connection.setInstanceFollowRedirects(true);

				int responseCode = connection.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_OK) {
					try (InputStream inputStream = connection.getInputStream()) {
						byte[] imageData = inputStream.readAllBytes();

						if (imageData != null && imageData.length > 0) {
							// Save to local file
							Files.write(localFile, imageData);

							// Return API URL format for frontend access:
							// /api/file-browser/download/{planId}?path={relativePath}
							String relativePath = rootPlanDir.relativize(localFile).toString().replace("\\", "/");
							// URL encode the path parameter
							String encodedPath = URLEncoder.encode(relativePath, StandardCharsets.UTF_8);
							String apiUrl = String.format("/api/file-browser/download/%s?path=%s", rootPlanId,
									encodedPath);
							localPaths.add(apiUrl);
							log.debug("Downloaded image {} to {} ({} bytes), API URL: {}", remoteUrl, localFile,
									imageData.length, apiUrl);
						}
						else {
							log.warn("Downloaded image data is empty for URL: {}", remoteUrl);
							// Fallback to remote URL if download fails
							localPaths.add(remoteUrl);
						}
					}
				}
				else {
					log.warn("Failed to download image from {}: HTTP response code {}", remoteUrl, responseCode);
					// Fallback to remote URL if download fails
					localPaths.add(remoteUrl);
				}

				connection.disconnect();
			}
			catch (Exception e) {
				log.error("Failed to download image from {}: {}", remoteUrl, e.getMessage(), e);
				// Fallback to remote URL if download fails
				localPaths.add(remoteUrl);
			}
		}

		return localPaths;
	}

	/**
	 * Validate and adjust image size according to model requirements
	 *
	 * wan2.6-t2i and wan2.5-t2i-preview: Total pixels between [768*768, 1440*1440] =
	 * [589824, 2073600] Aspect ratio between [1:4, 4:1] wan2.2 and below: Width and
	 * height between [512, 1440] pixels
	 * @param sizeStr Size string in format "WIDTHxHEIGHT" or "WIDTH*HEIGHT"
	 * @param modelName Model name to determine constraints
	 * @return Validated size string in format "WIDTH*HEIGHT"
	 */
	private String validateAndAdjustSize(String sizeStr, String modelName) {
		String lowerModelName = modelName != null ? modelName.toLowerCase() : "";
		boolean isNewModel = lowerModelName.contains("wan2.6-t2i") || lowerModelName.contains("wan2.5-t2i-preview");

		// Default sizes
		String defaultSize = isNewModel ? "1280*1280" : "1024*1024";

		if (sizeStr == null || sizeStr.trim().isEmpty()) {
			return defaultSize;
		}

		// Normalize separator (accept both "x" and "*")
		String normalized = sizeStr.trim().replace("x", "*").replace("X", "*");

		// Parse width and height
		String[] parts = normalized.split("\\*");
		if (parts.length != 2) {
			log.warn("Invalid size format: {}, using default: {}", sizeStr, defaultSize);
			return defaultSize;
		}

		try {
			int width = Integer.parseInt(parts[0].trim());
			int height = Integer.parseInt(parts[1].trim());

			if (isNewModel) {
				// wan2.6-t2i and wan2.5-t2i-preview: Total pixels between [589824,
				// 2073600]
				// Aspect ratio between [1:4, 4:1]
				long totalPixels = (long) width * height;

				// First, check and adjust aspect ratio if needed [1:4, 4:1]
				double aspectRatio = width > height ? (double) width / height : (double) height / width;
				if (aspectRatio > 4.0) {
					// Aspect ratio too extreme - adjust to 4:1 while maintaining total
					// pixels if possible
					if (width > height) {
						// Maintain width, adjust height
						height = width / 4;
					}
					else {
						// Maintain height, adjust width
						width = height / 4;
					}
					totalPixels = (long) width * height;
					log.info("Aspect ratio was too extreme, adjusted to {}*{}", width, height);
				}

				// Then adjust total pixels if needed
				if (totalPixels < 589824) {
					// Too small - scale up to minimum while maintaining aspect ratio
					double scale = Math.sqrt(589824.0 / totalPixels);
					width = (int) Math.round(width * scale);
					height = (int) Math.round(height * scale);
					totalPixels = (long) width * height;
					log.info("Size {} was too small, adjusted to {}*{} (total pixels: {})", sizeStr, width, height,
							totalPixels);
				}
				else if (totalPixels > 2073600) {
					// Too large - scale down to maximum while maintaining aspect ratio
					double scale = Math.sqrt(2073600.0 / totalPixels);
					width = (int) Math.round(width * scale);
					height = (int) Math.round(height * scale);
					totalPixels = (long) width * height;
					log.info("Size {} was too large, adjusted to {}*{} (total pixels: {})", sizeStr, width, height,
							totalPixels);
				}
			}
			else {
				// wan2.2 and below: Width and height between [512, 1440]
				if (width < 512) {
					width = 512;
					log.info("Width was too small, adjusted to 512");
				}
				else if (width > 1440) {
					width = 1440;
					log.info("Width was too large, adjusted to 1440");
				}

				if (height < 512) {
					height = 512;
					log.info("Height was too small, adjusted to 512");
				}
				else if (height > 1440) {
					height = 1440;
					log.info("Height was too large, adjusted to 1440");
				}

				// Check max resolution (1440*1440)
				long totalPixels = (long) width * height;
				if (totalPixels > 2073600) { // 1440 * 1440
					// Scale down proportionally
					double scale = Math.sqrt(2073600.0 / totalPixels);
					width = (int) Math.round(width * scale);
					height = (int) Math.round(height * scale);
					log.info("Total resolution was too large, adjusted to {}*{}", width, height);
				}
			}

			return width + "*" + height;
		}
		catch (NumberFormatException e) {
			log.warn("Invalid size format (non-numeric): {}, using default: {}", sizeStr, defaultSize);
			return defaultSize;
		}
	}

}

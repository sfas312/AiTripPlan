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

import com.alibaba.cloud.ai.lynxe.model.entity.DynamicModelEntity;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;

/**
 * Interface for image generation providers Different providers implement this interface
 * to support various image generation APIs
 */
public interface ImageGenerationProvider {

	/**
	 * Check if this provider supports the given model configuration
	 * @param modelEntity Model entity configuration
	 * @param modelName Model name
	 * @return true if this provider supports the model, false otherwise
	 */
	boolean supports(DynamicModelEntity modelEntity, String modelName);

	/**
	 * Generate image using the provider's API
	 * @param request Image generation request
	 * @param modelEntity Model entity configuration
	 * @param modelName Model name
	 * @param rootPlanId Root plan ID for saving images to local folder (optional, can be
	 * null)
	 * @return Tool execution result with image URLs (local file paths if rootPlanId is
	 * provided, otherwise remote URLs)
	 */
	ToolExecuteResult generateImage(ImageGenerationRequest request, DynamicModelEntity modelEntity, String modelName,
			String rootPlanId);

}

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
package com.alibaba.cloud.ai.lynxe.tool.pptGenerator.pptOperators;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.pptGenerator.PptGeneratorService;
import com.alibaba.cloud.ai.lynxe.tool.pptGenerator.PptInput;

/**
 * Create PPT tool that creates new PPT files or creates based on template. Supports .pptx
 * and .ppt file formats.
 */
public class CreatePptTool extends AbstractBaseTool<CreatePptTool.CreatePptInput> {

	private static final Logger log = LoggerFactory.getLogger(CreatePptTool.class);

	private static final String TOOL_NAME = "create-ppt";

	/**
	 * Input class for create PPT operations
	 */
	public static class CreatePptInput {

		private String title;

		private String subtitle;

		@com.fasterxml.jackson.annotation.JsonProperty("slide_contents")
		private List<SlideContent> slideContents;

		private String path;

		@com.fasterxml.jackson.annotation.JsonProperty("template_content")
		private String templateContent;

		@com.fasterxml.jackson.annotation.JsonProperty("file_name")
		private String fileName;

		// Getters and setters
		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getSubtitle() {
			return subtitle;
		}

		public void setSubtitle(String subtitle) {
			this.subtitle = subtitle;
		}

		public List<SlideContent> getSlideContents() {
			return slideContents;
		}

		public void setSlideContents(List<SlideContent> slideContents) {
			this.slideContents = slideContents;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getTemplateContent() {
			return templateContent;
		}

		public void setTemplateContent(String templateContent) {
			this.templateContent = templateContent;
		}

		public String getFileName() {
			return fileName;
		}

		public void setFileName(String fileName) {
			this.fileName = fileName;
		}

		/**
		 * Slide content class
		 */
		public static class SlideContent {

			private String title;

			private String content;

			@com.fasterxml.jackson.annotation.JsonProperty("image_path")
			private String imagePath;

			public String getTitle() {
				return title;
			}

			public void setTitle(String title) {
				this.title = title;
			}

			public String getContent() {
				return content;
			}

			public void setContent(String content) {
				this.content = content;
			}

			public String getImagePath() {
				return imagePath;
			}

			public void setImagePath(String imagePath) {
				this.imagePath = imagePath;
			}

		}

	}

	private final PptGeneratorService pptGeneratorService;

	private final ToolI18nService toolI18nService;

	public CreatePptTool(PptGeneratorService pptGeneratorService, ToolI18nService toolI18nService) {
		this.pptGeneratorService = pptGeneratorService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(CreatePptInput input) {
		log.info("CreatePptTool input: title={}, fileName={}", input.getTitle(), input.getFileName());
		try {
			String planId = this.currentPlanId;

			// Convert CreatePptInput to PptInput
			PptInput pptInput = convertToPptInput(input);

			// Update the file state to processing
			pptGeneratorService.updateFileState(planId, null, "Processing: Generating PPT file");

			String path = pptGeneratorService.createPpt(pptInput);

			// Update the file state to success
			pptGeneratorService.updateFileState(planId, path, "Success: PPT file generated successfully");

			return new ToolExecuteResult("PPT file generated successfully, save path: " + path);
		}
		catch (IllegalArgumentException e) {
			String planId = this.currentPlanId;
			pptGeneratorService.updateFileState(planId, null, "Error: Parameter validation failed: " + e.getMessage());
			return new ToolExecuteResult("Parameter validation failed: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("PPT generation failed", e);
			String planId = this.currentPlanId;
			pptGeneratorService.updateFileState(planId, null, "Error: PPT generation failed: " + e.getMessage());
			return new ToolExecuteResult("PPT generation failed: " + e.getMessage());
		}
	}

	/**
	 * Convert CreatePptInput to PptInput
	 */
	private PptInput convertToPptInput(CreatePptInput input) {
		PptInput pptInput = new PptInput();
		pptInput.setTitle(input.getTitle());
		pptInput.setSubtitle(input.getSubtitle());
		pptInput.setPath(input.getPath());
		pptInput.setTemplateContent(input.getTemplateContent());
		pptInput.setFileName(input.getFileName());

		// Convert slide contents
		if (input.getSlideContents() != null) {
			java.util.List<PptInput.SlideContent> pptSlideContents = new java.util.ArrayList<>();
			for (CreatePptInput.SlideContent sc : input.getSlideContents()) {
				PptInput.SlideContent pptSlideContent = new PptInput.SlideContent();
				pptSlideContent.setTitle(sc.getTitle());
				pptSlideContent.setContent(sc.getContent());
				pptSlideContent.setImagePath(sc.getImagePath());
				pptSlideContents.add(pptSlideContent);
			}
			pptInput.setSlideContents(pptSlideContents);
		}

		return pptInput;
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String planId = this.currentPlanId;
		String stateString;
		if (planId != null) {
			stateString = String.format("PPT Generator - Current File: %s, Last Operation: %s",
					pptGeneratorService.getCurrentFilePath(planId), pptGeneratorService.getLastOperationResult(planId));
		}
		else {
			stateString = "PPT Generator is ready";
		}
		return new ToolStateInfo(null, stateString);
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("create-ppt");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("create-ppt");
	}

	@Override
	public Class<CreatePptInput> getInputType() {
		return CreatePptInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			pptGeneratorService.cleanupForPlan(planId);
			log.info("Cleaning up PPT generator resources for plan: {}", planId);
		}
	}

	@Override
	public String getServiceGroup() {
		return "default";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

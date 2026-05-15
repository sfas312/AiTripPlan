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

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.pptGenerator.PptGeneratorService;

/**
 * Get PPT template list tool that retrieves available template list. Returns template
 * information in JSON format.
 */
public class GetPptTemplateListTool extends AbstractBaseTool<GetPptTemplateListTool.GetPptTemplateListInput> {

	private static final Logger log = LoggerFactory.getLogger(GetPptTemplateListTool.class);

	private static final String TOOL_NAME = "get-ppt-template-list";

	/**
	 * Input class for get PPT template list operations
	 */
	public static class GetPptTemplateListInput {

		// No parameters needed for this operation

	}

	private final PptGeneratorService pptGeneratorService;

	private final ToolI18nService toolI18nService;

	public GetPptTemplateListTool(PptGeneratorService pptGeneratorService, ToolI18nService toolI18nService) {
		this.pptGeneratorService = pptGeneratorService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(GetPptTemplateListInput input) {
		log.info("GetPptTemplateListTool input");
		try {
			String templateList = pptGeneratorService.getTemplateList();
			if (templateList == null || templateList.isEmpty()) {
				return new ToolExecuteResult(
						"No local templates, please check the folder extensions/pptGenerator/template available");
			}
			return new ToolExecuteResult(templateList);
		}
		catch (IOException e) {
			log.error("Failed to get template list", e);
			return new ToolExecuteResult("Failed to get template list: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("GetPptTemplateListTool execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		return new ToolStateInfo(null, "");
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("get-ppt-template-list");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("get-ppt-template-list");
	}

	@Override
	public Class<GetPptTemplateListInput> getInputType() {
		return GetPptTemplateListInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up PPT generator resources for plan: {}", planId);
			// Cleanup is handled by PptGeneratorService
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

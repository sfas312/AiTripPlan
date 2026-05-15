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
package com.alibaba.cloud.ai.lynxe.tool;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;

/**
 * Internal tool used to wrap LLM thinking messages when no tools are selected. This tool
 * requires the LLM to select at least one tool to proceed. This tool is not exposed to
 * LLM and is only used internally by DynamicAgent.
 */
public class ThinkTool extends AbstractBaseTool<Map<String, Object>> {

	private static final Logger log = LoggerFactory.getLogger(ThinkTool.class);

	public static final String name = "__think_internal__";

	public static final String SERVICE_GROUP = "internal";

	@Override
	public ToolExecuteResult run(Map<String, Object> input) {
		log.debug("ThinkTool called with input: {}", input);

		// Extract message from input
		Object messageObj = input != null ? input.get("message") : null;
		String message;

		if (messageObj == null) {
			message = "No message provided";
		}
		else if (messageObj instanceof String) {
			message = (String) messageObj;
		}
		else {
			message = messageObj.toString();
		}

		log.debug("ThinkTool message: {}", message);

		// Return a message that requires tool selection
		String result = message + "\n\n⚠️ IMPORTANT: You must call at least one tool to proceed. "
				+ "Do not provide explanations or reasoning - call a tool immediately.";

		return new ToolExecuteResult(result);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getDescription() {
		// Not exposed to LLM, return empty string
		return "";
	}

	@Override
	public String getParameters() {
		// Not exposed to LLM, return empty string
		return "";
	}

	@Override
	public Class<Map<String, Object>> getInputType() {
		@SuppressWarnings("unchecked")
		Class<Map<String, Object>> clazz = (Class<Map<String, Object>>) (Class<?>) Map.class;
		return clazz;
	}

	@Override
	public boolean isReturnDirect() {
		return true;
	}

	@Override
	public void cleanup(String planId) {
		// do nothing
	}

	@Override
	public String getServiceGroup() {
		return SERVICE_GROUP;
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String stateString = String.format("""
				Think Tool Status:
				- Tool Name: %s
				- Plan ID: %s
				- Status: Active (Internal Use Only)
				""", name, currentPlanId != null ? currentPlanId : "N/A");
		return new ToolStateInfo(null, stateString);
	}

	@Override
	public boolean isSelectable() {
		// Not selectable by LLM, only used internally
		return false;
	}

}

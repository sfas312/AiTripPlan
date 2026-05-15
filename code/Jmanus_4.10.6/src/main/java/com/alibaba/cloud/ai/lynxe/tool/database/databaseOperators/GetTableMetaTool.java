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
package com.alibaba.cloud.ai.lynxe.tool.database.databaseOperators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.database.action.GetTableMetaAction;
import com.alibaba.cloud.ai.lynxe.tool.database.service.DataSourceService;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GetTableMetaTool extends AbstractBaseTool<GetTableMetaTool.GetTableMetaInput> {

	private static final Logger log = LoggerFactory.getLogger(GetTableMetaTool.class);

	private static final String TOOL_NAME = "get-table-meta";

	/**
	 * Input class for get table meta operations
	 */
	public static class GetTableMetaInput {

		private String text;

		private String datasourceName;

		// Getters and setters
		public String getText() {
			return text;
		}

		public void setText(String text) {
			this.text = text;
		}

		public String getDatasourceName() {
			return datasourceName;
		}

		public void setDatasourceName(String datasourceName) {
			this.datasourceName = datasourceName;
		}

	}

	private final DataSourceService dataSourceService;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	public GetTableMetaTool(LynxeProperties lynxeProperties, DataSourceService dataSourceService,
			ObjectMapper objectMapper, ToolI18nService toolI18nService) {
		this.dataSourceService = dataSourceService;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public String getServiceGroup() {
		return "db-service";
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("get-table-meta-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("get-table-meta-tool");
	}

	@Override
	public Class<GetTableMetaInput> getInputType() {
		return GetTableMetaInput.class;
	}

	@Override
	public ToolExecuteResult run(GetTableMetaInput input) {
		log.info("GetTableMetaTool request: text={}, datasourceName={}", input.getText(), input.getDatasourceName());
		try {
			// Convert to DatabaseRequest for GetTableMetaAction
			DatabaseRequest request = new DatabaseRequest();
			request.setAction("get_table_meta");
			request.setText(input.getText());
			request.setDatasourceName(input.getDatasourceName());

			// First search with text, if not found then search all
			GetTableMetaAction metaAction = new GetTableMetaAction(objectMapper);
			ToolExecuteResult result = metaAction.execute(request, dataSourceService);
			if (result == null || result.getOutput() == null || result.getOutput().trim().isEmpty()
					|| result.getOutput().equals("[]") || result.getOutput().contains("No matching tables found")) {
				DatabaseRequest allReq = new DatabaseRequest();
				allReq.setAction("get_table_meta");
				allReq.setText(null);
				allReq.setDatasourceName(input.getDatasourceName());
				result = metaAction.execute(allReq, dataSourceService);
			}
			return result;
		}
		catch (Exception e) {
			log.error("GetTableMetaTool execution failed", e);
			return new ToolExecuteResult("Get table meta failed: " + e.getMessage());
		}
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up get table meta resources for plan: {}", planId);
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		return new ToolStateInfo(null, "");
	}

	public static GetTableMetaTool getInstance(DataSourceService dataSourceService, ObjectMapper objectMapper,
			ToolI18nService toolI18nService) {
		return new GetTableMetaTool(null, dataSourceService, objectMapper, toolI18nService);
	}

}

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
import com.alibaba.cloud.ai.lynxe.tool.database.action.GetDatasourceInfoAction;
import com.alibaba.cloud.ai.lynxe.tool.database.service.DataSourceService;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GetDatasourceInfoTool extends AbstractBaseTool<GetDatasourceInfoTool.GetDatasourceInfoInput> {

	private static final Logger log = LoggerFactory.getLogger(GetDatasourceInfoTool.class);

	private static final String TOOL_NAME = "get-datasource-info";

	/**
	 * Input class for get datasource info operations
	 */
	public static class GetDatasourceInfoInput {

		private String datasourceName;

		// Getters and setters
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

	public GetDatasourceInfoTool(LynxeProperties lynxeProperties, DataSourceService dataSourceService,
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
		return toolI18nService.getDescription("get-datasource-info-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("get-datasource-info-tool");
	}

	@Override
	public Class<GetDatasourceInfoInput> getInputType() {
		return GetDatasourceInfoInput.class;
	}

	@Override
	public ToolExecuteResult run(GetDatasourceInfoInput input) {
		log.info("GetDatasourceInfoTool request: datasourceName={}", input.getDatasourceName());
		try {
			// Convert to DatabaseRequest for GetDatasourceInfoAction
			DatabaseRequest request = new DatabaseRequest();
			request.setAction("get_datasource_info");
			request.setDatasourceName(input.getDatasourceName());

			return new GetDatasourceInfoAction(objectMapper).execute(request, dataSourceService);
		}
		catch (Exception e) {
			log.error("GetDatasourceInfoTool execution failed", e);
			return new ToolExecuteResult("Get datasource info failed: " + e.getMessage());
		}
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up get datasource info resources for plan: {}", planId);
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

	public static GetDatasourceInfoTool getInstance(DataSourceService dataSourceService, ObjectMapper objectMapper,
			ToolI18nService toolI18nService) {
		return new GetDatasourceInfoTool(null, dataSourceService, objectMapper, toolI18nService);
	}

}

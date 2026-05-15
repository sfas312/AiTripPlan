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

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.database.action.ExecuteSqlAction;
import com.alibaba.cloud.ai.lynxe.tool.database.service.DataSourceService;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;

/**
 * Execute read SQL tool that executes SELECT queries (read-only operations only).
 */
public class ExecuteReadSqlTool extends AbstractBaseTool<ExecuteReadSqlTool.ExecuteReadSqlInput> {

	private static final Logger log = LoggerFactory.getLogger(ExecuteReadSqlTool.class);

	private static final String TOOL_NAME = "execute-read-sql";

	/**
	 * Input class for execute read SQL operations
	 */
	public static class ExecuteReadSqlInput {

		private String query;

		private String datasourceName;

		private java.util.List<Object> parameters;

		// Getters and setters
		public String getQuery() {
			return query;
		}

		public void setQuery(String query) {
			this.query = query;
		}

		public String getDatasourceName() {
			return datasourceName;
		}

		public void setDatasourceName(String datasourceName) {
			this.datasourceName = datasourceName;
		}

		public java.util.List<Object> getParameters() {
			return parameters;
		}

		public void setParameters(java.util.List<Object> parameters) {
			this.parameters = parameters;
		}

	}

	private final DataSourceService dataSourceService;

	private final ToolI18nService toolI18nService;

	public ExecuteReadSqlTool(DataSourceService dataSourceService, ToolI18nService toolI18nService) {
		this.dataSourceService = dataSourceService;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ExecuteReadSqlInput input) {
		log.info("ExecuteReadSqlTool request: query={}", input.getQuery());
		try {
			String query = input.getQuery();
			if (query == null || query.trim().isEmpty()) {
				return new ToolExecuteResult("Error: query parameter is required");
			}

			// Validate that it's a SELECT query
			if (!query.trim().toUpperCase().startsWith("SELECT")) {
				return new ToolExecuteResult("Only SELECT queries are allowed in read-only mode");
			}

			// Convert to DatabaseRequest for ExecuteSqlAction
			DatabaseRequest request = new DatabaseRequest();
			request.setQuery(query);
			request.setDatasourceName(input.getDatasourceName());
			request.setParameters(input.getParameters());

			return new ExecuteSqlAction().execute(request, dataSourceService);
		}
		catch (Exception e) {
			log.error("ExecuteReadSqlTool execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String stateString;
		try {
			Map<String, String> datasourceInfo = dataSourceService.getAllDatasourceInfo();
			StringBuilder stateBuilder = new StringBuilder();
			stateBuilder.append("\n=== Execute Read SQL Tool Current State ===\n");

			if (datasourceInfo.isEmpty()) {
				stateBuilder.append("No datasources configured or available.\n");
			}
			else {
				stateBuilder.append("Available datasources:\n");
				for (Map.Entry<String, String> entry : datasourceInfo.entrySet()) {
					stateBuilder.append(String.format("  - %s (%s)\n", entry.getKey(), entry.getValue()));
				}
			}

			stateBuilder.append("\n=== End Execute Read SQL Tool State ===\n");
			stateString = stateBuilder.toString();
		}
		catch (Exception e) {
			log.error("Failed to get execute read SQL tool state", e);
			stateString = String.format("Execute read SQL tool state error: %s", e.getMessage());
		}
		return new ToolStateInfo(null, stateString);
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("execute-read-sql");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("execute-read-sql");
	}

	@Override
	public Class<ExecuteReadSqlInput> getInputType() {
		return ExecuteReadSqlInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up execute read SQL resources for plan: {}", planId);
		}
	}

	@Override
	public String getServiceGroup() {
		return "db-service";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}

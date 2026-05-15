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
import com.alibaba.cloud.ai.lynxe.tool.database.action.ExecuteSqlToJsonFileAction;
import com.alibaba.cloud.ai.lynxe.tool.database.service.DataSourceService;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Execute read SQL to JSON file tool that executes SELECT queries and saves results to
 * JSON file.
 */
public class ExecuteReadSqlToJsonFileTool
		extends AbstractBaseTool<ExecuteReadSqlToJsonFileTool.ExecuteReadSqlToJsonFileInput> {

	private static final Logger log = LoggerFactory.getLogger(ExecuteReadSqlToJsonFileTool.class);

	private static final String TOOL_NAME = "execute-read-sql-to-json-file-tool";

	/**
	 * Input class for execute read SQL to JSON file operations
	 */
	public static class ExecuteReadSqlToJsonFileInput {

		private String query;

		private String fileName;

		private String datasourceName;

		private java.util.List<Object> parameters;

		// Getters and setters
		public String getQuery() {
			return query;
		}

		public void setQuery(String query) {
			this.query = query;
		}

		public String getFileName() {
			return fileName;
		}

		public void setFileName(String fileName) {
			this.fileName = fileName;
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

	private final UnifiedDirectoryManager directoryManager;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	public ExecuteReadSqlToJsonFileTool(DataSourceService dataSourceService, UnifiedDirectoryManager directoryManager,
			ObjectMapper objectMapper, ToolI18nService toolI18nService) {
		this.dataSourceService = dataSourceService;
		this.directoryManager = directoryManager;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(ExecuteReadSqlToJsonFileInput input) {
		log.info("ExecuteReadSqlToJsonFileTool request: query={}, fileName={}", input.getQuery(), input.getFileName());
		try {
			String query = input.getQuery();
			if (query == null || query.trim().isEmpty()) {
				return new ToolExecuteResult("Error: query parameter is required");
			}

			String fileName = input.getFileName();
			if (fileName == null || fileName.trim().isEmpty()) {
				return new ToolExecuteResult("Error: fileName parameter is required");
			}

			// Validate that it's a SELECT query
			if (!query.trim().toUpperCase().startsWith("SELECT")) {
				return new ToolExecuteResult("Only SELECT queries are allowed in read-only mode");
			}

			// Convert to DatabaseRequest for ExecuteSqlToJsonFileAction
			DatabaseRequest request = new DatabaseRequest();
			request.setQuery(query);
			request.setFileName(fileName);
			request.setDatasourceName(input.getDatasourceName());
			request.setParameters(input.getParameters());

			return new ExecuteSqlToJsonFileAction(directoryManager, objectMapper, rootPlanId).execute(request,
					dataSourceService);
		}
		catch (Exception e) {
			log.error("ExecuteReadSqlToJsonFileTool execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String stateString;
		try {
			Map<String, String> datasourceInfo = dataSourceService.getAllDatasourceInfo();
			StringBuilder stateBuilder = new StringBuilder();
			stateBuilder.append("\n=== Execute Read SQL to JSON File Tool Current State ===\n");

			if (datasourceInfo.isEmpty()) {
				stateBuilder.append("No datasources configured or available.\n");
			}
			else {
				stateBuilder.append("Available datasources:\n");
				for (Map.Entry<String, String> entry : datasourceInfo.entrySet()) {
					stateBuilder.append(String.format("  - %s (%s)\n", entry.getKey(), entry.getValue()));
				}
			}

			stateBuilder.append("\n=== End Execute Read SQL to JSON File Tool State ===\n");
			stateString = stateBuilder.toString();
		}
		catch (Exception e) {
			log.error("Failed to get execute read SQL to JSON file tool state", e);
			stateString = String.format("Execute read SQL to JSON file tool state error: %s", e.getMessage());
		}
		return new ToolStateInfo(null, stateString);
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("execute-read-sql-to-json-file-tool");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("execute-read-sql-to-json-file-tool");
	}

	@Override
	public Class<ExecuteReadSqlToJsonFileInput> getInputType() {
		return ExecuteReadSqlToJsonFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up execute read SQL to JSON file resources for plan: {}", planId);
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

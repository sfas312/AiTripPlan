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
package com.alibaba.cloud.ai.lynxe.tool.todo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tool for creating and managing a structured task list (todos) for tracking progress on
 * complex, multi-step tasks. Todos are stored per rootPlanId in:
 * extensions/inner_storage/{rootPlanId}/todos.json
 */
public class TodoWriteTool extends AbstractBaseTool<TodoWriteInput> {

	private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

	public static final String NAME = "todo-manage";

	public static final String SERVICE_GROUP = "default";

	private final TodoStorageService todoStorageService;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	public TodoWriteTool(TodoStorageService todoStorageService, ObjectMapper objectMapper,
			ToolI18nService toolI18nService) {
		this.todoStorageService = todoStorageService;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
	}

	@Override
	public ToolExecuteResult run(TodoWriteInput input) {
		try {
			log.info("TodoWriteTool called with input: {}", objectMapper.writeValueAsString(input));

			// Validate input
			validateInput(input);

			// Get rootPlanId (required for storage)
			String rootPlanId = getRootPlanId();
			if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
				return new ToolExecuteResult(
						"Error: rootPlanId is required but not available. Cannot save todos without a plan context.");
			}

			// Handle user modifications if applicable
			if (Boolean.TRUE.equals(input.getModifiedByUser()) && input.getModifiedContent() != null) {
				log.info("User modified todos: {}", input.getModifiedContent());
				// For now, we'll use the todos from input directly
				// In the future, we could merge user modifications here
			}

			// Write todos to file
			todoStorageService.writeTodosToFile(input.getTodos(), rootPlanId);

			// Create structured result for UI
			Map<String, Object> resultData = new HashMap<>();
			resultData.put("type", "todo_list");
			resultData.put("todos", input.getTodos());

			// Convert to JSON string for frontend parsing
			String resultJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultData);

			// Create summary for LLM (avoid duplicating full JSON)
			List<TodoItem> todos = input.getTodos();
			long pendingCount = todos.stream().filter(t -> "pending".equals(t.getStatus())).count();
			long inProgressCount = todos.stream().filter(t -> "in_progress".equals(t.getStatus())).count();
			long completedCount = todos.stream().filter(t -> "completed".equals(t.getStatus())).count();

			// Add system reminder for LLM with summary instead of full JSON
			String systemReminder = "\n\n<system-reminder>\n"
					+ "Your todo list has been updated. DO NOT mention this explicitly to the user.\n" + "Summary: "
					+ todos.size() + " total todos (" + pendingCount + " pending, " + inProgressCount + " in progress, "
					+ completedCount + " completed).\n" + "Continue on with the tasks at hand if applicable.\n"
					+ "</system-reminder>";

			String output = resultJson + systemReminder;

			log.info("Successfully wrote {} todos for rootPlanId={}", input.getTodos().size(), rootPlanId);
			return new ToolExecuteResult(output);
		}
		catch (IllegalArgumentException e) {
			log.error("Validation error in TodoWriteTool: {}", e.getMessage(), e);
			return new ToolExecuteResult("Error: " + e.getMessage());
		}
		catch (Exception e) {
			log.error("Error executing TodoWriteTool", e);
			return new ToolExecuteResult("Error executing todo-write: " + e.getMessage());
		}
	}

	/**
	 * Validate input parameters
	 * @param input Input to validate
	 * @throws IllegalArgumentException if validation fails
	 */
	private void validateInput(TodoWriteInput input) {
		if (input == null) {
			throw new IllegalArgumentException("Input cannot be null");
		}

		if (input.getTodos() == null) {
			throw new IllegalArgumentException("Todos array is required");
		}

		List<TodoItem> todos = input.getTodos();
		if (todos.isEmpty()) {
			log.info("Empty todos list provided - this will clear the todo list");
			return; // Empty list is valid (clears todos)
		}

		// Validate each todo item
		Set<String> seenIds = new HashSet<>();
		for (int i = 0; i < todos.size(); i++) {
			TodoItem todo = todos.get(i);
			if (todo == null) {
				throw new IllegalArgumentException("Todo item at index " + i + " is null");
			}

			// Validate id
			if (todo.getId() == null || todo.getId().trim().isEmpty()) {
				throw new IllegalArgumentException("Todo item at index " + i + " has empty or null id");
			}

			// Validate content
			if (todo.getContent() == null || todo.getContent().trim().isEmpty()) {
				throw new IllegalArgumentException("Todo item at index " + i + " has empty or null content");
			}

			// Validate status
			String status = todo.getStatus();
			if (status == null || status.trim().isEmpty()) {
				throw new IllegalArgumentException("Todo item at index " + i + " has empty or null status");
			}

			// Check valid status values
			String normalizedStatus = status.toLowerCase().trim();
			if (!normalizedStatus.equals("pending") && !normalizedStatus.equals("in_progress")
					&& !normalizedStatus.equals("completed")) {
				throw new IllegalArgumentException("Todo item at index " + i + " has invalid status: " + status
						+ ". Valid values are: pending, in_progress, completed");
			}

			// Check for duplicate IDs
			if (seenIds.contains(todo.getId())) {
				throw new IllegalArgumentException("Duplicate todo id found: " + todo.getId());
			}
			seenIds.add(todo.getId());
		}
	}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("todo-write");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("todo-write");
	}

	@Override
	public Class<TodoWriteInput> getInputType() {
		return TodoWriteInput.class;
	}

	@Override
	public String getServiceGroup() {
		return SERVICE_GROUP;
	}

	@Override
	public ToolStateInfo getCurrentToolStateString() {
		String rootPlanId = getRootPlanId();
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			return new ToolStateInfo(SERVICE_GROUP, "Todo tool status: No plan context available");
		}

		try {
			List<TodoItem> todos = todoStorageService.readTodosFromFile(rootPlanId);
			if (todos.isEmpty()) {
				return new ToolStateInfo(SERVICE_GROUP, "Todo tool status: No todos found for current plan");
			}

			long pendingCount = todos.stream().filter(t -> "pending".equals(t.getStatus())).count();
			long inProgressCount = todos.stream().filter(t -> "in_progress".equals(t.getStatus())).count();
			long completedCount = todos.stream().filter(t -> "completed".equals(t.getStatus())).count();

			String stateString = String.format("""
					Todo tool status:
					- Total todos: %d
					- Pending: %d
					- In Progress: %d
					- Completed: %d
					""", todos.size(), pendingCount, inProgressCount, completedCount);

			return new ToolStateInfo(SERVICE_GROUP, stateString);
		}
		catch (Exception e) {
			log.warn("Failed to get todo state for rootPlanId={}: {}", rootPlanId, e.getMessage());
			return new ToolStateInfo(SERVICE_GROUP, "Todo tool status: Error reading todos");
		}
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

	@Override
	public void cleanup(String planId) {
		// No cleanup needed - todos persist in file system
		log.debug("TodoWriteTool cleanup called for planId={} (no action needed)", planId);
	}

}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for reading and writing todo lists to/from plan directories. Todos are stored
 * per rootPlanId in: extensions/inner_storage/{rootPlanId}/todos.json
 */
@Service
public class TodoStorageService {

	private static final Logger log = LoggerFactory.getLogger(TodoStorageService.class);

	private static final String TODOS_FILE_NAME = "todos.json";

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	private final ObjectMapper objectMapper;

	public TodoStorageService(UnifiedDirectoryManager unifiedDirectoryManager, ObjectMapper objectMapper) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.objectMapper = objectMapper;
	}

	/**
	 * Get the file path for todos.json in the plan directory
	 * @param rootPlanId The root plan ID
	 * @return Path to todos.json file
	 */
	public Path getTodoFilePath(String rootPlanId) {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			throw new IllegalArgumentException("rootPlanId cannot be null or empty");
		}
		Path rootPlanDir = unifiedDirectoryManager.getRootPlanDirectory(rootPlanId);
		return rootPlanDir.resolve(TODOS_FILE_NAME);
	}

	/**
	 * Read todos from file for the given rootPlanId
	 * @param rootPlanId The root plan ID
	 * @return List of TodoItem objects, or empty list if file doesn't exist
	 */
	public List<TodoItem> readTodosFromFile(String rootPlanId) {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			throw new IllegalArgumentException("rootPlanId cannot be null or empty");
		}

		Path todoFilePath = getTodoFilePath(rootPlanId);

		if (!Files.exists(todoFilePath)) {
			log.debug("Todo file does not exist for rootPlanId={}, returning empty list", rootPlanId);
			return new ArrayList<>();
		}

		try {
			if (!Files.isRegularFile(todoFilePath)) {
				log.warn("Todo path exists but is not a regular file for rootPlanId={}, path={}", rootPlanId,
						todoFilePath);
				return new ArrayList<>();
			}

			byte[] fileContent = Files.readAllBytes(todoFilePath);
			if (fileContent.length == 0) {
				log.debug("Todo file is empty for rootPlanId={}, returning empty list", rootPlanId);
				return new ArrayList<>();
			}

			// Parse JSON array of todos
			List<TodoItem> todos = objectMapper.readValue(fileContent, new TypeReference<List<TodoItem>>() {
			});

			log.debug("Successfully read {} todos from file for rootPlanId={}", todos.size(), rootPlanId);
			return todos;
		}
		catch (IOException e) {
			log.error("Failed to read todo file for rootPlanId={}, path={}: {}", rootPlanId, todoFilePath,
					e.getMessage(), e);
			throw new RuntimeException("Failed to read todos from file: " + e.getMessage(), e);
		}
		catch (Exception e) {
			log.error("Failed to parse todo file JSON for rootPlanId={}, path={}: {}", rootPlanId, todoFilePath,
					e.getMessage(), e);
			throw new RuntimeException("Failed to parse todos JSON: " + e.getMessage(), e);
		}
	}

	/**
	 * Write todos to file for the given rootPlanId
	 * @param todos List of TodoItem objects to write
	 * @param rootPlanId The root plan ID
	 */
	public void writeTodosToFile(List<TodoItem> todos, String rootPlanId) {
		if (rootPlanId == null || rootPlanId.trim().isEmpty()) {
			throw new IllegalArgumentException("rootPlanId cannot be null or empty");
		}

		if (todos == null) {
			throw new IllegalArgumentException("todos cannot be null");
		}

		Path todoFilePath = getTodoFilePath(rootPlanId);
		Path parentDir = todoFilePath.getParent();

		try {
			// Ensure parent directory exists
			if (parentDir != null && !Files.exists(parentDir)) {
				Files.createDirectories(parentDir);
				log.debug("Created parent directory for todos file: {}", parentDir);
			}

			// Serialize todos to JSON
			byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(todos);

			// Write to file
			Files.write(todoFilePath, jsonBytes);

			log.info("Successfully wrote {} todos to file for rootPlanId={}, path={}", todos.size(), rootPlanId,
					todoFilePath);
		}
		catch (IOException e) {
			log.error("Failed to write todo file for rootPlanId={}, path={}: {}", rootPlanId, todoFilePath,
					e.getMessage(), e);
			throw new RuntimeException("Failed to write todos to file: " + e.getMessage(), e);
		}
		catch (Exception e) {
			log.error("Failed to serialize todos to JSON for rootPlanId={}: {}", rootPlanId, e.getMessage(), e);
			throw new RuntimeException("Failed to serialize todos to JSON: " + e.getMessage(), e);
		}
	}

}

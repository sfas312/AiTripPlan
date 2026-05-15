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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input class for TodoWriteTool.
 */
public class TodoWriteInput {

	/**
	 * List of todo items to write
	 */
	@JsonProperty("todos")
	private List<TodoItem> todos;

	/**
	 * Whether the todos were modified by the user (optional)
	 */
	@JsonProperty("modified_by_user")
	private Boolean modifiedByUser;

	/**
	 * Modified content description (optional, used when modified_by_user is true)
	 */
	@JsonProperty("modified_content")
	private String modifiedContent;

	/**
	 * Default constructor for Jackson deserialization
	 */
	public TodoWriteInput() {
	}

	/**
	 * Constructor with todos list
	 * @param todos List of todo items
	 */
	public TodoWriteInput(List<TodoItem> todos) {
		this.todos = todos;
	}

	public List<TodoItem> getTodos() {
		return todos;
	}

	public void setTodos(List<TodoItem> todos) {
		this.todos = todos;
	}

	public Boolean getModifiedByUser() {
		return modifiedByUser;
	}

	public void setModifiedByUser(Boolean modifiedByUser) {
		this.modifiedByUser = modifiedByUser;
	}

	public String getModifiedContent() {
		return modifiedContent;
	}

	public void setModifiedContent(String modifiedContent) {
		this.modifiedContent = modifiedContent;
	}

	@Override
	public String toString() {
		return "TodoWriteInput{" + "todos=" + todos + ", modifiedByUser=" + modifiedByUser + ", modifiedContent='"
				+ modifiedContent + '\'' + '}';
	}

}

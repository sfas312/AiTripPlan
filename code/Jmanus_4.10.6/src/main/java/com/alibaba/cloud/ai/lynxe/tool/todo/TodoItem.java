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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single todo item in the todo list.
 */
public class TodoItem {

	/**
	 * Unique identifier for the todo item
	 */
	@JsonProperty("id")
	private String id;

	/**
	 * Content/description of the todo item
	 */
	@JsonProperty("content")
	private String content;

	/**
	 * Status of the todo item: "pending", "in_progress", or "completed"
	 */
	@JsonProperty("status")
	private String status;

	/**
	 * Default constructor for Jackson deserialization
	 */
	public TodoItem() {
	}

	/**
	 * Constructor with all fields
	 * @param id Unique identifier
	 * @param content Content/description
	 * @param status Status (pending, in_progress, completed)
	 */
	public TodoItem(String id, String content, String status) {
		this.id = id;
		this.content = content;
		this.status = status;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	@Override
	public String toString() {
		return "TodoItem{" + "id='" + id + '\'' + ", content='" + content + '\'' + ", status='" + status + '\'' + '}';
	}

}

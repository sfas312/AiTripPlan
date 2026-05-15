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

/**
 * Bean class representing tool state information with a key for deduplication
 */
public class ToolStateInfo {

	/**
	 * Unique identifier for deduplication (e.g., service group name)
	 */
	private String key;

	/**
	 * The actual state information string
	 */
	private String stateString;

	public ToolStateInfo() {
	}

	public ToolStateInfo(String key, String stateString) {
		this.key = key;
		this.stateString = stateString;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getStateString() {
		return stateString;
	}

	public void setStateString(String stateString) {
		this.stateString = stateString;
	}

	@Override
	public String toString() {
		return stateString != null ? stateString : "";
	}

}

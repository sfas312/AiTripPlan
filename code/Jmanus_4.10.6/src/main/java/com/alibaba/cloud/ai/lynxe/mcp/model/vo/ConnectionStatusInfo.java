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
package com.alibaba.cloud.ai.lynxe.mcp.model.vo;

import java.time.LocalDateTime;

/**
 * Connection status information
 */
public class ConnectionStatusInfo {

	private McpConnectionStatus status;

	private String errorMessage;

	private LocalDateTime lastErrorTime;

	public ConnectionStatusInfo() {
		this.status = McpConnectionStatus.DISCONNECTED;
	}

	public ConnectionStatusInfo(McpConnectionStatus status) {
		this.status = status;
	}

	public McpConnectionStatus getStatus() {
		return status;
	}

	public void setStatus(McpConnectionStatus status) {
		this.status = status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		if (errorMessage != null) {
			this.lastErrorTime = LocalDateTime.now();
		}
	}

	public LocalDateTime getLastErrorTime() {
		return lastErrorTime;
	}

	public void setLastErrorTime(LocalDateTime lastErrorTime) {
		this.lastErrorTime = lastErrorTime;
	}

}

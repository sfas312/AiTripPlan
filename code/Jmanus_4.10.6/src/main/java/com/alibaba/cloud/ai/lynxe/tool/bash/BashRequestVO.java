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
package com.alibaba.cloud.ai.lynxe.tool.bash;

/**
 * Bash tool request object for encapsulating bash operation request parameters Supports
 * action-based interface similar to BrowserUseTool
 */
public class BashRequestVO {

	/**
	 * Bash operation type Supports: command, send_input, terminate
	 */
	private String action;

	/**
	 * Command to execute, used for 'command' action
	 */
	private String command;

	/**
	 * Input to send to current interactive process, used for 'send_input' action
	 * Examples: 'n' for next page, 'q' for quit, '\n' for Enter key
	 */
	private String input;

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public String getInput() {
		return input;
	}

	public void setInput(String input) {
		this.input = input;
	}

}

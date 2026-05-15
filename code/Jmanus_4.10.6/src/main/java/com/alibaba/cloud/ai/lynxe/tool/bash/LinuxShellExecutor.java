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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux command executor implementation
 */
public class LinuxShellExecutor implements ShellCommandExecutor {

	private static final Logger log = LoggerFactory.getLogger(LinuxShellExecutor.class);

	private Process currentProcess;

	private static final int DEFAULT_TIMEOUT = 60; // Default timeout (seconds)

	@SuppressWarnings("unused")
	private BufferedWriter processInput;

	@Override
	public List<String> execute(List<String> commands, String workingDir) {
		return commands.stream().map(command -> {
			try {
				// If the command is empty, return the extra logs of the current process
				if (command.trim().isEmpty() && currentProcess != null) {
					return processOutput(currentProcess);
				}

				// If the command is ctrl+c, send the interrupt signal
				if ("ctrl+c".equalsIgnoreCase(command.trim()) && currentProcess != null) {
					terminate();
					return "Process terminated by ctrl+c";
				}

				ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
				if (!StringUtils.isEmpty(workingDir)) {
					pb.directory(new File(workingDir));
				}

				// Set Linux environment variables
				pb.environment().put("LANG", "en_US.UTF-8");
				pb.environment().put("SHELL", "/bin/bash");
				pb.environment().put("PATH", System.getenv("PATH") + ":/usr/local/bin");
				// Disable all pagers to ensure direct output without pagination
				pb.environment().put("PAGER", "cat");
				pb.environment().put("GIT_PAGER", "cat");
				pb.environment().put("MANPAGER", "cat");
				pb.environment().put("LESS", "-R");
				pb.environment().put("MORE", "-R");

				currentProcess = pb.start();
				processInput = new BufferedWriter(new OutputStreamWriter(currentProcess.getOutputStream()));

				// Start reading output immediately to prevent deadlocks
				// Read output while process is running, not after it completes
				StringBuilder outputBuilder = new StringBuilder();
				StringBuilder errorBuilder = new StringBuilder();

				// Use ExecutorService to read stdout and stderr concurrently
				ExecutorService executor = Executors.newFixedThreadPool(2);

				try {
					// Read standard output in a separate thread
					Future<StringBuilder> stdoutFuture = executor.submit(() -> {
						StringBuilder builder = new StringBuilder();
						try (BufferedReader reader = new BufferedReader(
								new InputStreamReader(currentProcess.getInputStream(), "UTF-8"))) {
							String line;
							while ((line = reader.readLine()) != null) {
								log.info(line);
								builder.append(line).append("\n");
							}
						}
						catch (IOException e) {
							log.error("Error reading stdout", e);
						}
						return builder;
					});

					// Read error output in a separate thread
					Future<StringBuilder> stderrFuture = executor.submit(() -> {
						StringBuilder builder = new StringBuilder();
						try (BufferedReader errorReader = new BufferedReader(
								new InputStreamReader(currentProcess.getErrorStream(), "UTF-8"))) {
							String line;
							while ((line = errorReader.readLine()) != null) {
								log.error(line);
								builder.append(line).append("\n");
							}
						}
						catch (IOException e) {
							log.error("Error reading stderr", e);
						}
						return builder;
					});

					// Wait for process to complete with timeout
					if (!command.endsWith("&")) { // Only set timeout if the command is
													// not a background command
						if (!currentProcess.waitFor(DEFAULT_TIMEOUT, TimeUnit.SECONDS)) {
							log.warn("Command timed out. Sending SIGINT to the process");
							terminate();
							// Retry the command in the background
							if (!command.endsWith("&")) {
								command += " &";
							}
							return execute(Collections.singletonList(command), workingDir).get(0);
						}
					}

					// Wait for both reading threads to complete (with timeout to prevent
					// hanging)
					try {
						outputBuilder = stdoutFuture.get(5, TimeUnit.SECONDS);
					}
					catch (TimeoutException e) {
						log.warn("Timeout waiting for stdout reader, forcing shutdown");
						stdoutFuture.cancel(true);
					}

					try {
						errorBuilder = stderrFuture.get(5, TimeUnit.SECONDS);
					}
					catch (TimeoutException e) {
						log.warn("Timeout waiting for stderr reader, forcing shutdown");
						stderrFuture.cancel(true);
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return "Error: Process interrupted - " + e.getMessage();
				}
				catch (Exception e) {
					log.error("Error reading process output", e);
				}
				finally {
					executor.shutdown();
					try {
						if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
							executor.shutdownNow();
						}
					}
					catch (InterruptedException e) {
						executor.shutdownNow();
						Thread.currentThread().interrupt();
					}
				}

				// Return result based on exit code
				int exitCode = currentProcess.isAlive() ? -1 : currentProcess.exitValue();
				if (exitCode == 0) {
					return outputBuilder.toString();
				}
				else if (exitCode == -1) {
					return "Process is still running. Use empty command to get more logs, or 'ctrl+c' to terminate.";
				}
				else {
					return "Error (Exit Code " + exitCode + "): "
							+ (errorBuilder.length() > 0 ? errorBuilder.toString() : outputBuilder.toString());
				}
			}
			catch (Throwable e) {
				log.error("Exception executing Linux command", e);
				return "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
			}
		}).collect(Collectors.toList());
	}

	@Override
	public void terminate() {
		if (currentProcess != null && currentProcess.isAlive()) {
			// First try sending SIGINT (ctrl+c)
			currentProcess.destroy();
			try {
				// Wait for process to respond to SIGINT
				if (!currentProcess.waitFor(5, TimeUnit.SECONDS)) {
					// If process doesn't respond to SIGINT, force terminate
					currentProcess.destroyForcibly();
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				currentProcess.destroyForcibly();
			}
			log.info("Linux process terminated");
		}
	}

	private String processOutput(Process process) throws IOException, InterruptedException {
		StringBuilder outputBuilder = new StringBuilder();
		StringBuilder errorBuilder = new StringBuilder();

		// Use ExecutorService to read stdout and stderr concurrently to prevent deadlocks
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			// Read standard output in a separate thread
			Future<StringBuilder> stdoutFuture = executor.submit(() -> {
				StringBuilder builder = new StringBuilder();
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(), "UTF-8"))) {
					String line;
					while ((line = reader.readLine()) != null) {
						log.info(line);
						builder.append(line).append("\n");
					}
				}
				catch (IOException e) {
					log.error("Error reading stdout", e);
				}
				return builder;
			});

			// Read error output in a separate thread
			Future<StringBuilder> stderrFuture = executor.submit(() -> {
				StringBuilder builder = new StringBuilder();
				try (BufferedReader errorReader = new BufferedReader(
						new InputStreamReader(process.getErrorStream(), "UTF-8"))) {
					String line;
					while ((line = errorReader.readLine()) != null) {
						log.error(line);
						builder.append(line).append("\n");
					}
				}
				catch (IOException e) {
					log.error("Error reading stderr", e);
				}
				return builder;
			});

			// Wait for both threads to complete with timeout to prevent hanging
			try {
				outputBuilder = stdoutFuture.get(5, TimeUnit.SECONDS);
			}
			catch (TimeoutException e) {
				log.warn("Timeout waiting for stdout reader, forcing shutdown");
				stdoutFuture.cancel(true);
			}

			try {
				errorBuilder = stderrFuture.get(5, TimeUnit.SECONDS);
			}
			catch (TimeoutException e) {
				log.warn("Timeout waiting for stderr reader, forcing shutdown");
				stderrFuture.cancel(true);
			}
		}
		catch (Exception e) {
			log.error("Error reading process output", e);
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
		finally {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			}
			catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

		int exitCode = process.isAlive() ? -1 : process.exitValue();
		if (exitCode == 0) {
			return outputBuilder.toString();
		}
		else if (exitCode == -1) {
			return "Process is still running. Use empty command to get more logs, or 'ctrl+c' to terminate.";
		}
		else {
			return "Error (Exit Code " + exitCode + "): "
					+ (errorBuilder.length() > 0 ? errorBuilder.toString() : outputBuilder.toString());
		}
	}

}

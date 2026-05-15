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
 * Mac command executor implementation
 */
public class MacShellExecutor implements ShellCommandExecutor {

	private static final Logger log = LoggerFactory.getLogger(MacShellExecutor.class);

	private Process currentProcess;

	private static final int DEFAULT_TIMEOUT = 60; // Default timeout in seconds

	@SuppressWarnings("unused")
	private BufferedWriter processInput;

	// Cache for shell path to avoid repeated detection
	private static String shellPath = null;

	@Override
	public List<String> execute(List<String> commands, String workingDir) {
		return commands.stream().map(command -> {
			try {
				// If empty command, return additional logs from current process
				if (command.trim().isEmpty() && currentProcess != null) {
					return processOutput(currentProcess);
				}

				// If ctrl+c command, send interrupt signal
				if ("ctrl+c".equalsIgnoreCase(command.trim()) && currentProcess != null) {
					terminate();
					return "Process terminated by ctrl+c";
				}

				// Use dynamic shell path detection
				String shell = getShellPath();
				ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
				if (!StringUtils.isEmpty(workingDir)) {
					pb.directory(new File(workingDir));
				}

				// Set environment variables
				pb.environment().put("LANG", "en_US.UTF-8");
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
					if (!command.endsWith("&")) { // Only set timeout for non-background
													// commands
						if (!currentProcess.waitFor(DEFAULT_TIMEOUT, TimeUnit.SECONDS)) {
							log.warn("Command timed out. Sending SIGINT to the process");
							terminate();
							// Retry command in background
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
				log.error("Exception executing Mac command", e);
				return "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
			}
		}).collect(Collectors.toList());
	}

	@Override
	public void terminate() {
		if (currentProcess != null && currentProcess.isAlive()) {
			// First try to send SIGINT (ctrl+c)
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
			log.info("Mac process terminated");
		}
	}

	/**
	 * Dynamically detect the best available shell path Priority: zsh -> bash (as
	 * fallback)
	 * @return The path to the shell executable
	 */
	private String getShellPath() {
		// Return cached path if already detected
		if (shellPath != null) {
			return shellPath;
		}

		// Try to find zsh in common locations
		String[] zshPaths = { "/bin/zsh", "/usr/bin/zsh", "/usr/local/bin/zsh", "/opt/homebrew/bin/zsh" };

		for (String path : zshPaths) {
			if (new File(path).exists() && new File(path).canExecute()) {
				log.info("Found zsh at: {}", path);
				shellPath = path;
				return shellPath;
			}
		}

		// If zsh not found, use 'which' command to find it
		try {
			Process whichProcess = new ProcessBuilder("which", "zsh").start();
			whichProcess.waitFor(5, TimeUnit.SECONDS);
			if (whichProcess.exitValue() == 0) {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(whichProcess.getInputStream()))) {
					String path = reader.readLine();
					if (path != null && !path.trim().isEmpty()) {
						File zshFile = new File(path.trim());
						if (zshFile.exists() && zshFile.canExecute()) {
							log.info("Found zsh via 'which' command at: {}", path.trim());
							shellPath = path.trim();
							return shellPath;
						}
					}
				}
			}
		}
		catch (Exception e) {
			log.warn("Failed to find zsh using 'which' command: {}", e.getMessage());
		}

		// Fall back to bash if zsh is not available
		String[] bashPaths = { "/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash" };

		for (String path : bashPaths) {
			if (new File(path).exists() && new File(path).canExecute()) {
				log.warn("zsh not found, falling back to bash at: {}", path);
				shellPath = path;
				return shellPath;
			}
		}

		// Final fallback - use system default
		log.error("Neither zsh nor bash found in standard locations, using /bin/bash as final fallback");
		shellPath = "/bin/bash";
		return shellPath;
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

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
package com.alibaba.cloud.ai.lynxe.runtime.executor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Interface for providing executor pools for task execution. Allows custom
 * implementations to provide their own executor pools.
 */
public interface ExecutorPoolProvider {

	/**
	 * Get an executor service for the specified depth level
	 * @param depthLevel The depth level (0 is root level)
	 * @return ExecutorService for the specified level
	 */
	ExecutorService getExecutorForLevel(int depthLevel);

	/**
	 * Submit a task to the executor for the specified depth level
	 * @param depthLevel The depth level
	 * @param task The task to execute
	 * @param <T> The type of the task result
	 * @return CompletableFuture representing the task execution
	 */
	<T> CompletableFuture<T> submitTask(int depthLevel, Callable<T> task);

	/**
	 * Submit a runnable task to the executor for the specified depth level
	 * @param depthLevel The depth level
	 * @param task The runnable task to execute
	 * @return CompletableFuture representing the task execution
	 */
	CompletableFuture<Void> submitTask(int depthLevel, Runnable task);

}

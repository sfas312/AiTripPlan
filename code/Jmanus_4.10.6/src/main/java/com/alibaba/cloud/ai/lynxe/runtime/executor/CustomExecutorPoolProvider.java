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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Example custom executor pool provider implementation. This is a simple example - you
 * can create your own implementation with custom logic.
 *
 * To use your custom executor pool provider: 1. Create a class implementing
 * ExecutorPoolProvider 2. Annotate it with @Component (or @Service) 3. Spring will
 * automatically use your implementation instead of LevelBasedExecutorPool
 *
 * Note: If you have multiple ExecutorPoolProvider beans, you may need to use @Primary
 * or @Qualifier to specify which one to use.
 */
@Component
@Primary // This makes this the default provider instead of LevelBasedExecutorPool
public class CustomExecutorPoolProvider implements ExecutorPoolProvider {

	private static final Logger log = LoggerFactory.getLogger(CustomExecutorPoolProvider.class);

	// Example: Use a single shared executor for all levels
	// You can customize this to have different executors per level
	private final ExecutorService sharedExecutor;

	public CustomExecutorPoolProvider() {
		// Create your custom executor pool here
		// Example: Fixed thread pool with 20 threads
		this.sharedExecutor = Executors.newFixedThreadPool(20, new ThreadFactory() {
			private final AtomicInteger threadCounter = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r, "custom-executor-thread-" + threadCounter.getAndIncrement());
				thread.setDaemon(false);
				return thread;
			}
		});
		log.info("Created custom executor pool provider with shared executor (20 threads)");
	}

	@Override
	public ExecutorService getExecutorForLevel(int depthLevel) {
		// Example: Return the same executor for all levels
		// You can customize this to return different executors based on depthLevel
		log.debug("Getting executor for depth level: {}", depthLevel);
		return sharedExecutor;
	}

	@Override
	public <T> CompletableFuture<T> submitTask(int depthLevel, Callable<T> task) {
		ExecutorService executor = getExecutorForLevel(depthLevel);
		return CompletableFuture.supplyAsync(() -> {
			try {
				return task.call();
			}
			catch (Exception e) {
				log.error("Task execution failed at depth level {}: {}", depthLevel, e.getMessage(), e);
				throw new CompletionException(e);
			}
		}, executor);
	}

	@Override
	public CompletableFuture<Void> submitTask(int depthLevel, Runnable task) {
		ExecutorService executor = getExecutorForLevel(depthLevel);
		return CompletableFuture.runAsync(() -> {
			try {
				task.run();
			}
			catch (Exception e) {
				log.error("Task execution failed at depth level {}: {}", depthLevel, e.getMessage(), e);
				throw new CompletionException(e);
			}
		}, executor);
	}

}

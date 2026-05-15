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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.agent.AgentState;
import com.alibaba.cloud.ai.lynxe.agent.BaseAgent;
import com.alibaba.cloud.ai.lynxe.agent.entity.DynamicAgentEntity;
import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionContext;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.PlanExecutionResult;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.PlanInterface;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.StepResult;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.FileUploadService;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * Abstract base class for plan executors. Contains common logic and basic functionality
 * for all executor types.
 */
public abstract class AbstractPlanExecutor implements PlanExecutorInterface {

	private static final Logger logger = LoggerFactory.getLogger(AbstractPlanExecutor.class);

	protected final PlanExecutionRecorder recorder;

	// Pattern to match square brackets at the beginning of a string, supports
	// Chinese and
	// other characters
	protected final Pattern pattern = Pattern.compile("^\\s*\\[([^\\]]+)\\]");

	protected final List<DynamicAgentEntity> agents;

	protected final LevelBasedExecutorPool levelBasedExecutorPool;

	protected AgentInterruptionHelper agentInterruptionHelper;

	protected LlmService llmService;

	protected final LynxeProperties lynxeProperties;

	protected final FileUploadService fileUploadService;

	protected final UnifiedDirectoryManager unifiedDirectoryManager;

	protected PlanIdDispatcher planIdDispatcher;

	/**
	 * Generate a unique step ID for tracking execution steps using PlanIdDispatcher.
	 * @return unique step ID
	 */
	protected String generateStepId() {
		if (planIdDispatcher != null) {
			return planIdDispatcher.generateStepId();
		}
		else {
			throw new IllegalStateException("PlanIdDispatcher is not available");
		}
	}

	// Define static final strings for the keys used in executorParams
	public static final String PLAN_STATUS_KEY = "planStatus";

	public static final String CURRENT_STEP_INDEX_KEY = "currentStepIndex";

	public static final String STEP_TEXT_KEY = "stepText";

	public static final String TITLE_KEY = "title";

	public static final String RECURSIVE_CALL_CHAIN_KEY = "recursiveCallChain";

	public AbstractPlanExecutor(List<DynamicAgentEntity> agents, PlanExecutionRecorder recorder, LlmService llmService,
			LynxeProperties lynxeProperties, LevelBasedExecutorPool levelBasedExecutorPool,
			FileUploadService fileUploadService, AgentInterruptionHelper agentInterruptionHelper,
			UnifiedDirectoryManager unifiedDirectoryManager, PlanIdDispatcher planIdDispatcher) {
		this.agents = agents;
		this.recorder = recorder;
		this.llmService = llmService;
		this.lynxeProperties = lynxeProperties;
		this.levelBasedExecutorPool = levelBasedExecutorPool;
		this.fileUploadService = fileUploadService;
		this.agentInterruptionHelper = agentInterruptionHelper;
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.planIdDispatcher = planIdDispatcher;
	}

	/**
	 * General logic for executing a single step.
	 * @param step The execution step
	 * @param context The execution context
	 * @return CompletableFuture that completes with the step executor
	 */
	protected CompletableFuture<BaseAgent> executeStep(ExecutionStep step, ExecutionContext context) {
		// Ensure stepId is set - generate if null (e.g., from JSON deserialization)
		if (step.getStepId() == null) {
			String generatedStepId = generateStepId();
			step.setStepId(generatedStepId);
			logger.debug("Generated stepId for ExecutionStep: {}", generatedStepId);
		}

		BaseAgent executor = getExecutorForStep(context, step);
		if (executor == null) {
			logger.error("No executor found for step type: {}", step.getStepInStr());
			step.setResult("No executor found for step type: " + step.getStepInStr());
			step.setStatus(AgentState.FAILED);
			step.setErrorMessage("No executor found for step type: " + step.getStepInStr());
			return CompletableFuture.completedFuture(null);
		}

		step.setAgent(executor);

		try {
			recorder.recordStepStart(step, context.getCurrentPlanId());
		}
		catch (Exception e) {
			logger.warn("Failed to record step start for planId: {}", context.getCurrentPlanId(), e);
		}

		// Chain async execution
		return executor.run().thenApply(agentResult -> {
			if (agentResult == null) {
				logger.error("Agent {} returned null result", executor.getName());
				step.setResult("Agent execution returned null result");
				step.setStatus(AgentState.FAILED);
				step.setErrorMessage("Agent execution returned null result");
				context.setSuccess(false);
				return executor;
			}

			step.setResult(agentResult.getResult());
			step.setStatus(agentResult.getState());

			// Check if agent was interrupted, completed, or failed
			if (agentResult.getState() == AgentState.INTERRUPTED) {
				logger.info("Agent {} was interrupted during step execution", executor.getName());
				// Don't return null, return the executor so interruption can be handled
				// at plan level
			}
			else if (agentResult.getState() == AgentState.COMPLETED) {
				logger.info("Agent {} completed step execution", executor.getName());
			}
			else if (agentResult.getState() == AgentState.FAILED) {
				logger.error("Agent {} failed during step execution", executor.getName());
				// Set success to false for plan level handling
				context.setSuccess(false);
			}

			return executor;
		}).exceptionally(e -> {
			logger.error("Error executing step: {} for planId: {}", step.getStepRequirement(),
					context.getCurrentPlanId(), e);
			String errorMessage = e.getMessage();
			if (errorMessage == null || errorMessage.isEmpty()) {
				errorMessage = e.getClass().getSimpleName() + " occurred during step execution";
			}
			step.setResult("Execution failed: " + errorMessage);
			step.setErrorMessage(errorMessage);
			return null;
		}).thenApply(executorResult -> {
			try {
				recorder.recordStepEnd(step, context.getCurrentPlanId());
			}
			catch (Exception e) {
				logger.warn("Failed to record step end for planId: {}", context.getCurrentPlanId(), e);
			}
			return executorResult;
		});
	}

	/**
	 * Extract the step type from the step requirement string.
	 */
	protected String getStepFromStepReq(String stepRequirement) {
		Matcher matcher = pattern.matcher(stepRequirement);
		if (matcher.find()) {
			return matcher.group(1).trim().toUpperCase();
		}
		return "DEFAULT_AGENT";
	}

	/**
	 * Initialize plan execution environment, including symbolic link creation for root
	 * plans
	 * @param context The execution context containing plan information
	 */
	protected void initializePlanExecution(ExecutionContext context) {
		// Initialize symbolic link for root plan (only for root plans, not sub-plans)
		if (unifiedDirectoryManager != null && context.getRootPlanId() != null
				&& context.getRootPlanId().equals(context.getCurrentPlanId())) {
			try {
				Path rootPlanDir = unifiedDirectoryManager.getRootPlanDirectory(context.getRootPlanId());
				unifiedDirectoryManager.ensureExternalFolderLink(rootPlanDir, context.getRootPlanId());
				logger.debug("Initialized external folder symbolic link for rootPlanId: {}", context.getRootPlanId());
			}
			catch (Exception e) {
				logger.warn("Failed to initialize external folder symbolic link for rootPlanId: {}",
						context.getRootPlanId(), e);
				// Continue execution even if symbolic link creation fails
			}
		}
	}

	/**
	 * Synchronize uploaded files to plan directory if uploadKey is provided
	 * @param context The execution context containing uploadKey and rootPlanId
	 */
	protected void syncUploadedFilesToPlan(ExecutionContext context) {
		String uploadKey = context.getUploadKey();
		String rootPlanId = context.getRootPlanId();

		if (uploadKey != null && !uploadKey.trim().isEmpty() && rootPlanId != null && !rootPlanId.trim().isEmpty()) {
			try {
				logger.info("Synchronizing uploaded files from uploadKey: {} to rootPlanId: {}", uploadKey, rootPlanId);
				fileUploadService.syncUploadedFilesToPlan(uploadKey, rootPlanId);
				logger.info("Successfully synchronized uploaded files for plan execution");
			}
			catch (Exception e) {
				logger.warn(
						"Failed to synchronize uploaded files from uploadKey: {} to rootPlanId: {}. Continuing execution without file sync.",
						uploadKey, rootPlanId, e);
			}
		}
		else {
			logger.debug("No uploadKey provided or rootPlanId missing, skipping file synchronization");
		}
	}

	/**
	 * Get the executor for the step.
	 */
	protected abstract BaseAgent getExecutorForStep(ExecutionContext context, ExecutionStep step);

	protected PlanExecutionRecorder getRecorder() {
		return recorder;
	}

	/**
	 * Execute all steps asynchronously and return a CompletableFuture with execution
	 * results. Uses level-based executor pools based on plan depth.
	 *
	 * Usage example:
	 *
	 * <pre>
	 * CompletableFuture<PlanExecutionResult> future = planExecutor.executeAllStepsAsync(context);
	 *
	 * future.whenComplete((result, throwable) -> {
	 *   if (throwable != null) {
	 *     // Handle execution error
	 *     System.err.println("Execution failed: " + throwable.getMessage());
	 *   } else {
	 *     // Handle successful completion
	 *     if (result.isSuccess()) {
	 *       String finalResult = result.getEffectiveResult();
	 *       System.out.println("Final result: " + finalResult);
	 *
	 *       // Access individual step results
	 *       for (StepResult step : result.getStepResults()) {
	 *         System.out.println("Step " + step.getStepIndex() + ": " + step.getResult());
	 *       }
	 *     } else {
	 *       System.err.println("Execution failed: " + result.getErrorMessage());
	 *     }
	 *   }
	 * });
	 * </pre>
	 * @param context Execution context containing user request and execution process
	 * information
	 * @return CompletableFuture containing PlanExecutionResult with all step results
	 */
	public CompletableFuture<PlanExecutionResult> executeAllStepsAsync(ExecutionContext context) {
		// Initialize plan execution synchronously (setup work)
		PlanInterface plan = context.getPlan();
		if (plan == null) {
			return CompletableFuture.completedFuture(createErrorResult(context, "Plan is null in execution context"));
		}
		plan.setCurrentPlanId(context.getCurrentPlanId());
		plan.setRootPlanId(context.getRootPlanId());
		plan.updateStepIndices();

		// Initialize plan execution environment
		initializePlanExecution(context);

		// Synchronize uploaded files to plan directory at the beginning of execution
		syncUploadedFilesToPlan(context);
		List<ExecutionStep> steps = plan.getAllSteps();

		// Ensure all ExecutionStep objects have stepId before recording
		if (steps != null) {
			for (ExecutionStep step : steps) {
				if (step.getStepId() == null) {
					String generatedStepId = generateStepId();
					step.setStepId(generatedStepId);
					logger.debug("Generated stepId for ExecutionStep before recordPlanExecutionStart: {}",
							generatedStepId);
				}
			}
		}

		recorder.recordPlanExecutionStart(context.getCurrentPlanId(), context.getPlan().getTitle(), context.getTitle(),
				steps, context.getParentPlanId(), context.getRootPlanId(), context.getToolCallId());

		// Build async chain for step execution
		PlanExecutionResult result = new PlanExecutionResult();
		CompletableFuture<BaseAgent> chain = CompletableFuture.completedFuture(null);

		if (steps != null && !steps.isEmpty()) {
			for (int i = 0; i < steps.size(); i++) {
				final ExecutionStep step = steps.get(i);
				final int stepIndex = i;
				chain = chain.thenCompose(lastExecutor -> {
					// Check for interruption before each step
					if (agentInterruptionHelper != null
							&& !agentInterruptionHelper.checkInterruptionAndContinue(context.getRootPlanId())) {
						logger.info("Plan execution interrupted at step {}/{} for planId: {}", stepIndex + 1,
								steps.size(), context.getRootPlanId());
						context.setSuccess(false);
						result.setSuccess(false);
						result.setErrorMessage("Plan execution interrupted by user");
						return CompletableFuture.completedFuture(null);
					}

					return executeStep(step, context).thenApply(stepExecutor -> {
						if (stepExecutor != null) {
							// Collect step result
							StepResult stepResult = new StepResult();
							stepResult.setStepIndex(step.getStepIndex());
							stepResult.setStepRequirement(step.getStepRequirement());
							stepResult.setResult(step.getResult());
							stepResult.setStatus(step.getStatus());
							stepResult.setAgentName(stepExecutor.getName());

							result.addStepResult(stepResult);

							// Check if this step was interrupted
							if (step.getResult() != null
									&& step.getResult().contains("Execution interrupted by user")) {
								logger.info("Step execution was interrupted, stopping plan execution");
								context.setSuccess(false);
								result.setSuccess(false);
								result.setErrorMessage("Plan execution interrupted by user");
								return null; // Signal to stop
							}

							// Check if this step failed
							if (step.getStatus() == AgentState.FAILED) {
								logger.error("Step execution failed, stopping plan execution");
								context.setSuccess(false);
								result.setSuccess(false);
								if (step.getErrorMessage() != null && !step.getErrorMessage().isEmpty()) {
									result.setErrorMessage(step.getErrorMessage());
								}
								else {
									result.setErrorMessage("Agent execution failed: " + step.getResult());
								}
								return null; // Signal to stop
							}

							return stepExecutor;
						}
						return null;
					});
				});
			}
		}

		// Finalize result and perform cleanup
		return chain.thenApply(lastExecutor -> {
			// Only set success if no interruption or failure occurred
			if (result.getErrorMessage() == null || (!result.getErrorMessage().contains("interrupted")
					&& !result.getErrorMessage().contains("failed"))) {
				context.setSuccess(true);
				result.setSuccess(true);
				result.setFinalResult(context.getPlan().getResult());
			}

			// Perform cleanup
			try {
				performCleanup(context, lastExecutor);
			}
			catch (Exception e) {
				logger.error("Error during cleanup for planId: {}", context.getCurrentPlanId(), e);
			}

			return result;
		}).exceptionally(throwable -> {
			logger.error("Unexpected error during plan execution for planId: {}", context.getCurrentPlanId(),
					throwable);
			context.setSuccess(false);
			result.setSuccess(false);
			String errorMessage = throwable.getMessage();
			if (errorMessage == null || errorMessage.isEmpty()) {
				errorMessage = throwable.getClass().getSimpleName() + " occurred during plan execution";
			}
			result.setErrorMessage(errorMessage);

			// Perform cleanup on error
			try {
				performCleanup(context, null);
			}
			catch (Exception e) {
				logger.error("Error during cleanup in exceptionally handler for planId: {}", context.getCurrentPlanId(),
						e);
			}

			return result;
		});
	}

	private PlanExecutionResult createErrorResult(ExecutionContext context, String errorMessage) {
		PlanExecutionResult errorResult = new PlanExecutionResult();
		errorResult.setSuccess(false);
		errorResult.setErrorMessage(errorMessage);
		context.setSuccess(false);
		return errorResult;
	}

	/**
	 * Cleanup work after execution is completed.
	 */
	protected void performCleanup(ExecutionContext context, BaseAgent lastExecutor) {
		String planId = context.getCurrentPlanId();
		if (lastExecutor != null) {
			lastExecutor.clearUp(planId);
		}
		// Remove symbolic link directory when root plan task finishes
		// Only clean up for root plan (currentPlanId == rootPlanId)
		String rootPlanId = context.getRootPlanId();
		if (unifiedDirectoryManager != null && rootPlanId != null && rootPlanId.equals(planId)) {
			try {
				logger.info("Attempting to remove external folder link for rootPlanId: {}, currentPlanId: {}",
						rootPlanId, planId);
				unifiedDirectoryManager.removeExternalFolderLink(rootPlanId);
				logger.info("Successfully removed external folder link for rootPlanId: {}", rootPlanId);
			}
			catch (Exception e) {
				logger.error("Failed to remove external folder symbolic link for rootPlanId: {}", rootPlanId, e);
			}
		}
		else {
			if (unifiedDirectoryManager == null) {
				logger.info("Skipping linked_external cleanup: unifiedDirectoryManager is null");
			}
			else if (rootPlanId == null) {
				logger.info("Skipping linked_external cleanup: rootPlanId is null, currentPlanId: {}", planId);
			}
			else if (!rootPlanId.equals(planId)) {
				logger.info(
						"Skipping linked_external cleanup: currentPlanId ({}) != rootPlanId ({}) - this is a sub-plan",
						planId, rootPlanId);
			}
		}
	}

}

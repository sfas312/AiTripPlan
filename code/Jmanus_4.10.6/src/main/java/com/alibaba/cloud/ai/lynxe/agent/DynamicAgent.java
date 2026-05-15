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
package com.alibaba.cloud.ai.lynxe.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import com.alibaba.cloud.ai.lynxe.agent.entity.AgentStreamingResult;
import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.event.LynxeEventPublisher;
import com.alibaba.cloud.ai.lynxe.event.PlanExceptionClearedEvent;
import com.alibaba.cloud.ai.lynxe.exception.TokenLimitExceededException;
import com.alibaba.cloud.ai.lynxe.llm.ConversationMemoryLimitService;
import com.alibaba.cloud.ai.lynxe.llm.LlmService;
import com.alibaba.cloud.ai.lynxe.llm.StreamingResponseHandler;
import com.alibaba.cloud.ai.lynxe.llm.TokenCountService;
import com.alibaba.cloud.ai.lynxe.llm.TokenLimitService;
import com.alibaba.cloud.ai.lynxe.planning.PlanningFactory.ToolCallBackContext;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder.ActToolParam;
import com.alibaba.cloud.ai.lynxe.recorder.service.PlanExecutionRecorder.ThinkActRecordParams;
import com.alibaba.cloud.ai.lynxe.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.lynxe.runtime.executor.AbstractPlanExecutor;
import com.alibaba.cloud.ai.lynxe.runtime.service.AgentInterruptionHelper;
import com.alibaba.cloud.ai.lynxe.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.lynxe.runtime.service.ServiceGroupIndexService;
import com.alibaba.cloud.ai.lynxe.runtime.service.TaskInterruptionCheckerService;
import com.alibaba.cloud.ai.lynxe.runtime.service.UserInputService;
import com.alibaba.cloud.ai.lynxe.subplan.model.vo.SubplanToolWrapper;
import com.alibaba.cloud.ai.lynxe.tool.ErrorReportTool;
import com.alibaba.cloud.ai.lynxe.tool.FormInputTool;
import com.alibaba.cloud.ai.lynxe.tool.SystemErrorReportTool;
import com.alibaba.cloud.ai.lynxe.tool.TerminableTool;
import com.alibaba.cloud.ai.lynxe.tool.ThinkTool;
import com.alibaba.cloud.ai.lynxe.tool.ToolCallBiFunctionDef;
import com.alibaba.cloud.ai.lynxe.tool.ToolStateInfo;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.mapreduce.ParallelExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.common.util.StringUtils;
import reactor.core.publisher.Flux;

public class DynamicAgent extends ReActAgent {

	private static final String CURRENT_STEP_ENV_DATA_KEY = "current_step_env_data";

	private static final Logger log = LoggerFactory.getLogger(DynamicAgent.class);

	/**
	 * Dedicated thread pool for FormInputTool waiting operations. Uses 5 threads to
	 * handle user input waiting without blocking business thread pools.
	 */
	private static final ExecutorService FORM_INPUT_WAIT_EXECUTOR = Executors.newFixedThreadPool(5, r -> {
		Thread t = new Thread(r, "form-input-wait-thread-" + System.nanoTime());
		t.setDaemon(true);
		return t;
	});

	private final ObjectMapper objectMapper;

	private final String agentName;

	private final String agentDescription;

	private final String nextStepPrompt;

	protected ToolCallbackProvider toolCallbackProvider;

	protected final List<String> availableToolKeys;

	private ChatResponse response;

	private StreamingResponseHandler.StreamingResult streamResult;

	private AgentStreamingResult agentStreamingResult;

	private Prompt userPrompt;

	private List<ActToolParam> actToolInfoList = new ArrayList<>();

	private final ToolCallingManager toolCallingManager;

	private final UserInputService userInputService;

	private final String modelName;

	private final StreamingResponseHandler streamingResponseHandler;

	private LynxeEventPublisher lynxeEventPublisher;

	private AgentInterruptionHelper agentInterruptionHelper;

	private ParallelExecutionService parallelExecutionService;

	private ConversationMemoryLimitService conversationMemoryLimitService;

	private ServiceGroupIndexService serviceGroupIndexService;

	/**
	 * List to record all exceptions from LLM calls during retry attempts
	 */
	private final List<Exception> llmCallExceptions = new ArrayList<>();

	/**
	 * Latest exception from LLM calls, used when max retries are reached
	 */
	private Exception latestLlmException = null;

	/**
	 * Track the last N tool call results to detect loops
	 */
	private static final int REPEATED_RESULT_THRESHOLD = 3;

	private final List<String> recentToolResults = new ArrayList<>();

	/**
	 * Agent memory stored as a list of messages (replaces ChatMemory-based agentMemory)
	 */
	private List<Message> agentMessages = new ArrayList<>();

	/**
	 * Extra messages stored as a list. Set during agent initialization to avoid repeated
	 * retrieval from ChatMemory.
	 */
	private List<Message> extraMessage = new ArrayList<>();

	/**
	 * Model context limit (in tokens) used for the current think-act cycle. Set during
	 * checkAndCompressMemoryIfNeeded() and used when recording ThinkActRecord.
	 */
	private Integer currentModelContextLimit = null;

	public void clearUp(String planId) {
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		for (ToolCallBackContext toolCallBack : toolCallBackContext.values()) {
			try {
				toolCallBack.getFunctionInstance().cleanup(planId);
			}
			catch (Exception e) {
				log.error("Error cleaning up tool callback context: {}", e.getMessage(), e);
			}
		}
		// Also remove any pending form input tool for this root plan ID
		if (userInputService != null) {
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}
		}
	}

	public DynamicAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
			LynxeProperties lynxeProperties, String name, String description, String nextStepPrompt,
			List<String> availableToolKeys, ToolCallingManager toolCallingManager,
			Map<String, Object> initialAgentSetting, UserInputService userInputService, String modelName,
			StreamingResponseHandler streamingResponseHandler, ExecutionStep step, PlanIdDispatcher planIdDispatcher,
			LynxeEventPublisher lynxeEventPublisher, AgentInterruptionHelper agentInterruptionHelper,
			ObjectMapper objectMapper, ParallelExecutionService parallelExecutionService,
			ConversationMemoryLimitService conversationMemoryLimitService,
			ServiceGroupIndexService serviceGroupIndexService, List<Message> extraMessage) {
		super(llmService, planExecutionRecorder, lynxeProperties, initialAgentSetting, step, planIdDispatcher);
		this.objectMapper = objectMapper;
		super.objectMapper = objectMapper; // Set parent's objectMapper as well
		this.agentName = name;
		this.agentDescription = description;
		this.nextStepPrompt = nextStepPrompt;
		if (availableToolKeys == null) {
			this.availableToolKeys = new ArrayList<>();
		}
		else {
			this.availableToolKeys = availableToolKeys;
		}
		this.toolCallingManager = toolCallingManager;
		this.userInputService = userInputService;
		this.modelName = modelName;
		this.streamingResponseHandler = streamingResponseHandler;
		this.lynxeEventPublisher = lynxeEventPublisher;
		this.agentInterruptionHelper = agentInterruptionHelper;
		this.parallelExecutionService = parallelExecutionService;
		this.conversationMemoryLimitService = conversationMemoryLimitService;
		this.serviceGroupIndexService = serviceGroupIndexService;
		this.extraMessage = extraMessage != null ? new ArrayList<>(extraMessage) : new ArrayList<>();
	}

	@Override
	protected boolean think() {
		// Check for interruption before starting thinking process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} thinking process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			// Throw exception to signal interruption instead of returning false
			throw new TaskInterruptionCheckerService.TaskInterruptedException(
					"Agent thinking interrupted for rootPlanId: " + getRootPlanId());
		}

		collectAndSetEnvDataForTools();

		try {
			boolean result = executeWithRetry(3);
			// If retries exhausted and we have exceptions, the result will be false
			// and latestLlmException will be set
			return result;
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			log.info("Agent {} thinking process interrupted: {}", getName(), e.getMessage());
			throw e; // Re-throw the interruption exception
		}
		catch (Exception e) {
			log.error(String.format("🚨 Oops! The %s's thinking process hit a snag: %s", getName(), e.getMessage()), e);
			log.info("Exception occurred", e);
			// Record this exception as well
			latestLlmException = e;
			llmCallExceptions.add(e);
			return false;
		}
	}

	private boolean executeWithRetry(int maxRetries) throws Exception {
		int attempt = 0;
		Exception lastException = null;
		// Track no-tool-selected count to add IMPORTANT hints when repeated
		int noToolSelectedCount = 0;
		// Clear exception list at the start of retry cycle
		llmCallExceptions.clear();
		latestLlmException = null;

		while (attempt < maxRetries) {
			attempt++;

			// Check for interruption before each retry attempt
			if (agentInterruptionHelper != null
					&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
				log.info("Agent {} retry process interrupted at attempt {}/{} for rootPlanId: {}", getName(), attempt,
						maxRetries, getRootPlanId());
				throw new TaskInterruptionCheckerService.TaskInterruptedException(
						"Agent thinking interrupted at attempt " + attempt);
			}

			try {
				log.info("Attempt {}/{}: Executing agent thinking process", attempt, maxRetries);

				Message systemMessage = getThinkMessage();
				// Use current env as user message
				Message currentStepEnvMessage = currentStepEnvMessage();

				// If no tools were selected in previous attempts, add explicit tool call
				// requirement
				if (noToolSelectedCount > 0) {
					String toolCallRequirement = String.format(
							"\n\n IMPORTANT: You must call at least one tool to proceed. "
									+ "Previous %d attempt(s) did not select any tools. "
									+ "Do not provide explanations or reasoning - call a tool immediately.",
							noToolSelectedCount);
					// Append requirement to current step env message
					String enhancedEnvText = currentStepEnvMessage.getText() + toolCallRequirement;
					// Create new UserMessage with enhanced text, preserving metadata
					UserMessage enhancedMessage = new UserMessage(enhancedEnvText);
					if (currentStepEnvMessage.getMetadata() != null) {
						enhancedMessage.getMetadata().putAll(currentStepEnvMessage.getMetadata());
					}
					currentStepEnvMessage = enhancedMessage;
					log.info("Added explicit tool call requirement to retry message (no tool selected count: {})",
							noToolSelectedCount);
				}
				// Record think message
				List<Message> thinkMessages = Arrays.asList(systemMessage, currentStepEnvMessage);
				String thinkInput = thinkMessages.toString();

				// Merge extraMessage into agentMessages at the first round
				if (getCurrentStep() == 1 && extraMessage != null && !extraMessage.isEmpty()) {
					log.debug("First round: merging {} extra messages into agentMessages for conversationId: {}",
							extraMessage.size(), getConversationId());
					agentMessages.addAll(0, extraMessage);
					// Clear extraMessage after merging to avoid duplicate processing
					extraMessage.clear();
				}

				// Check and compress memory after merging extraMessage (if first round)
				// and before building prompt
				// This calculates the full prompt token count, compresses if needed, and
				// checks against model context limit
				int inputTokenCount = checkAndCompressMemoryIfNeeded(systemMessage, currentStepEnvMessage);

				// Validate inputTokenCount
				if (inputTokenCount <= 0) {
					throw new IllegalStateException(
							"Failed to calculate input token count. TokenCountService or ConversationMemoryLimitService must be available.");
				}

				// log.debug("Messages prepared for the prompt: {}", thinkMessages);
				// Build current prompt. System message is the first message
				List<Message> messages = new ArrayList<>();
				// Add history message from agent memory (already contains extraMessage if
				// first round, and may be compressed)
				List<Message> historyMem = agentMessages;

				messages.addAll(Collections.singletonList(systemMessage));
				// Add historyMem (agent memory) in every round
				messages.addAll(historyMem);
				log.debug("Added {} history messages from agent memory for round {}", historyMem.size(),
						getCurrentStep());

				messages.add(currentStepEnvMessage);

				String toolcallId = planIdDispatcher.generateToolCallId();
				// Call the LLM
				Map<String, Object> toolContextMap = new HashMap<>();
				toolContextMap.put("toolcallId", toolcallId);
				toolContextMap.put("planDepth", getPlanDepth());
				// NOTE: Do NOT add recursive call chain here - it should only be in tool
				// execution contexts
				// Adding it here can cause serialization issues with Spring AI
				ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
					.internalToolExecutionEnabled(false)
					.toolContext(toolContextMap)
					// can't support by toocall options :
					// .parallelToolCalls(lynxeProperties.getParallelToolCalls())
					.build();
				userPrompt = new Prompt(messages, chatOptions);
				List<ToolCallback> callbacks = getToolCallList();
				ChatClient chatClient;
				if (modelName == null || modelName.isEmpty()) {
					chatClient = llmService.getDefaultDynamicAgentChatClient();
				}
				else {
					chatClient = llmService.getDynamicAgentChatClient(modelName);
				}

				// Use streaming response handler for better user experience and content
				// merging
				Flux<ChatResponse> responseFlux = chatClient.prompt(userPrompt)
					.toolCallbacks(callbacks)
					.stream()
					.chatResponse();
				boolean isDebugModel = lynxeProperties.getDebugDetail() != null && lynxeProperties.getDebugDetail();
				// Enable early termination for agent thinking (should have tool calls)
				// Pass token count directly to StreamingResponseHandler
				streamResult = streamingResponseHandler.processStreamingResponse(responseFlux,
						"Agent " + getName() + " thinking", getCurrentPlanId(), isDebugModel, true, inputTokenCount);

				// Extract commonly used data into AgentStreamingResult
				List<ToolCall> toolCalls = streamResult.getEffectiveToolCalls();
				String responseByLLm = streamResult.getEffectiveText();
				int finalInputTokenCount = streamResult.getInputTokenCount();
				int finalOutputTokenCount = streamResult.getOutputTokenCount();

				agentStreamingResult = new AgentStreamingResult(toolCalls, responseByLLm, finalInputTokenCount,
						finalOutputTokenCount);

				// Keep response for backward compatibility (used in
				// extractAssistantMessageFromResponse)
				response = streamResult.getLastResponse();

				log.info("Input token count: {}, Output token count: {}", agentStreamingResult.getInputTokenCount(),
						agentStreamingResult.getOutputTokenCount());

				log.info(String.format("✨ %s's thoughts: %s", getName(), agentStreamingResult.getResponseText()));
				log.info(String.format("🛠️ %s selected %d tools to use", getName(),
						agentStreamingResult.getToolCalls().size()));

				// If no tools selected, wrap message in ThinkTool and create a ToolCall
				if (!agentStreamingResult.hasToolCalls()) {
					noToolSelectedCount++;
					log.warn("Attempt {}: No tools selected. Creating ThinkTool call... (no tool selected count: {})",
							attempt, noToolSelectedCount);

					try {
						// Prepare ThinkTool input
						Map<String, Object> thinkToolInput = new HashMap<>();
						thinkToolInput.put("message", agentStreamingResult.getResponseText() != null
								? agentStreamingResult.getResponseText() : "No response from LLM");

						// Create ThinkTool ToolCall
						String thinkToolCallId = planIdDispatcher.generateToolCallId();
						String thinkToolArguments = objectMapper.writeValueAsString(thinkToolInput);
						ToolCall thinkToolCall = new ToolCall(thinkToolCallId, "function",
								ThinkTool.SERVICE_GROUP + "-" + ThinkTool.name, thinkToolArguments);

						// Add ThinkTool call to agentStreamingResult
						List<ToolCall> toolCallsWithThink = new ArrayList<>();
						toolCallsWithThink.add(thinkToolCall);
						agentStreamingResult.setToolCalls(toolCallsWithThink);

						log.info("Created ThinkTool call, will be executed in unified tool processing flow");
					}
					catch (Exception e) {
						log.error("Failed to create ThinkTool call: {}", e.getMessage(), e);
						// Continue with normal retry flow if ThinkTool creation fails
					}
				}

				// Unified tool processing flow (handles both regular tools and ThinkTool)
				if (agentStreamingResult.hasToolCalls()) {
					// Reset no-tool-selected count on successful tool call
					noToolSelectedCount = 0;
					log.info(String.format("🧰 Tools being prepared: %s",
							agentStreamingResult.getToolCalls()
								.stream()
								.map(ToolCall::name)
								.collect(Collectors.toList())));

					String stepId = super.step.getStepId();
					String thinkActId = planIdDispatcher.generateThinkActId();

					actToolInfoList = new ArrayList<>();
					// Generate unique toolCallId for each tool when multiple tools are
					// present
					// This ensures each tool has its own toolCallId for proper sub-plan
					// linkage
					for (ToolCall toolCall : agentStreamingResult.getToolCalls()) {
						String toolCallIdForTool = (agentStreamingResult.getToolCalls().size() > 1)
								? planIdDispatcher.generateToolCallId() : toolcallId;
						ActToolParam actToolInfo = new ActToolParam(toolCall.name(), toolCall.arguments(),
								toolCallIdForTool);
						actToolInfoList.add(actToolInfo);
					}

					ThinkActRecordParams paramsN = new ThinkActRecordParams(thinkActId, stepId, thinkInput,
							agentStreamingResult.getResponseText(), null, agentStreamingResult.getInputTokenCount(),
							agentStreamingResult.getOutputTokenCount(), currentModelContextLimit, actToolInfoList);
					planExecutionRecorder.recordThinkingAndAction(step, paramsN);
					// Reset after recording
					currentModelContextLimit = null;

					// Clear exception cache if this was a retry attempt
					if (attempt > 1 && lynxeEventPublisher != null) {
						log.info("Retry successful for planId: {}, clearing exception cache", getCurrentPlanId());
						lynxeEventPublisher.publish(new PlanExceptionClearedEvent(getCurrentPlanId()));
					}

					return true;
				}

			}
			catch (Exception e) {
				lastException = e;
				latestLlmException = e;
				// Record exception to the list (record all exceptions, even non-retryable
				// ones)
				llmCallExceptions.add(e);
				log.warn("Attempt {} failed: {}", attempt, e.getMessage());
				log.debug("Exception details for attempt {}: {}", attempt, e.getMessage(), e);

				// Check if this is a network-related error that should be retried
				if (isRetryableException(e)) {
					if (attempt < maxRetries) {
						long waitTime = calculateBackoffDelay(attempt);
						log.info("Retrying in {}ms due to retryable error: {}", waitTime, e.getMessage());
						try {
							Thread.sleep(waitTime);
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new Exception("Retry interrupted", ie);
						}
						continue;
					}
				}
				else {
					// Non-retryable error - still record it, but throw immediately
					log.error("Non-retryable error encountered at attempt {}/{}: {}", attempt, maxRetries,
							e.getMessage());
					throw e;
				}
			}
		}

		// All retries exhausted
		if (lastException != null) {
			log.error("All {} retry attempts failed. Total exceptions recorded: {}. Latest exception: {}", maxRetries,
					llmCallExceptions.size(), latestLlmException != null ? latestLlmException.getMessage() : "N/A");
			// Store the latest exception for use in step() method
			// Don't throw exception here, let think() return false and step() handle it
			return false;
		}
		return false;
	}

	/**
	 * Check if the exception is retryable (network issues, timeouts, etc.)
	 */
	private boolean isRetryableException(Exception e) {
		String message = e.getMessage();
		if (message == null)
			return false;

		// Check for network-related errors
		return message.contains("Failed to resolve") || message.contains("timeout") || message.contains("connection")
				|| message.contains("DNS") || message.contains("WebClientRequestException")
				|| message.contains("DnsNameResolverTimeoutException");
	}

	/**
	 * Calculate exponential backoff delay
	 */
	private long calculateBackoffDelay(int attempt) {
		// Exponential backoff: 2^attempt * 2000ms, max 60 seconds
		long delay = Math.min(2000L * (1L << (attempt - 1)), 60000L);
		return delay;
	}

	@Override
	public CompletableFuture<AgentExecResult> step() {
		try {
			boolean shouldAct = think();
			if (!shouldAct) {
				// Check if we have a latest exception from LLM calls (max retries
				// reached)
				if (latestLlmException != null) {
					log.error(
							"Agent {} thinking failed after all retries. Simulating full flow with SystemErrorReportTool",
							getName());
					return CompletableFuture.completedFuture(handleLlmTimeoutWithSystemErrorReport());
				}
				// No tools selected after all retries - require LLM to output tool calls
				log.warn("Agent {} did not select any tools after all retries. Requiring tool call.", getName());
				return CompletableFuture.completedFuture(new AgentExecResult(
						"No tools were selected. You must select and call at least one tool to proceed. Please retry with tool calls.",
						AgentState.IN_PROGRESS));
			}
			// Chain act() async result
			return act();
		}
		catch (TaskInterruptionCheckerService.TaskInterruptedException e) {
			// Agent was interrupted, return INTERRUPTED state to stop execution
			return CompletableFuture.completedFuture(
					new AgentExecResult("Agent execution interrupted: " + e.getMessage(), AgentState.INTERRUPTED));
		}
		catch (Exception e) {
			log.error("Unexpected exception in step()", e);
			return CompletableFuture.completedFuture(handleExceptionWithSystemErrorReport(e, new ArrayList<>()));
		}
	}

	/**
	 * Get the list of all exceptions recorded during LLM calls
	 * @return List of exceptions (may be empty if no exceptions occurred)
	 */
	public List<Exception> getLlmCallExceptions() {
		return new ArrayList<>(llmCallExceptions); // Return a copy to prevent external
													// modification
	}

	/**
	 * Get the latest exception from LLM calls
	 * @return Latest exception, or null if no exceptions occurred
	 */
	public Exception getLatestLlmException() {
		return latestLlmException;
	}

	/**
	 * Build error message from the latest exception with context information
	 * @param functionName Name of the function that failed (e.g., "think()", "act()")
	 * @return Formatted error message with exception details and context
	 */
	private String buildErrorMessageFromLatestException(String functionName) {
		if (latestLlmException == null) {
			return "Unknown error occurred during LLM call";
		}

		StringBuilder errorMessage = new StringBuilder();
		errorMessage.append("LLM call failed after all retry attempts.\n");

		// Add function name
		if (functionName != null && !functionName.isEmpty()) {
			errorMessage.append("Function: ").append(functionName).append("\n");
		}

		// Add agent name
		if (agentName != null && !agentName.isEmpty()) {
			errorMessage.append("Agent: ").append(agentName).append("\n");
		}

		// Add step number
		int stepNumber = getCurrentStep();
		errorMessage.append("Step: ").append(stepNumber).append("\n");

		// Add model name
		String effectiveModelName = modelName;
		if (effectiveModelName == null || effectiveModelName.isEmpty()) {
			effectiveModelName = llmService != null ? llmService.getDefaultModelName() : "unknown";
		}
		errorMessage.append("Model: ").append(effectiveModelName).append("\n");

		// Add input token count if available
		int inputTokenCount = -1;
		if (agentStreamingResult != null && agentStreamingResult.getInputTokenCount() > 0) {
			inputTokenCount = agentStreamingResult.getInputTokenCount();
		}
		else if (streamResult != null && streamResult.getInputTokenCount() > 0) {
			inputTokenCount = streamResult.getInputTokenCount();
		}

		// Handle TokenLimitExceededException specially
		if (latestLlmException instanceof TokenLimitExceededException tokenLimitException) {
			int currentTokens = tokenLimitException.getCurrentTokens();
			int limit = tokenLimitException.getLimit();
			String exceptionModelName = tokenLimitException.getModelName();
			errorMessage.append("Input Tokens: ").append(currentTokens).append(" (Limit: ").append(limit).append(")\n");
			if (exceptionModelName != null && !exceptionModelName.equals(effectiveModelName)) {
				errorMessage.append("Model (from exception): ").append(exceptionModelName).append("\n");
			}
		}
		else if (inputTokenCount > 0) {
			errorMessage.append("Input Tokens: ").append(inputTokenCount).append("\n");
		}

		// Add prompt summary (first message or truncated)
		if (userPrompt != null && userPrompt.getInstructions() != null && !userPrompt.getInstructions().isEmpty()) {
			String promptSummary = buildPromptSummary(userPrompt.getInstructions());
			if (promptSummary != null && !promptSummary.isEmpty()) {
				errorMessage.append("Prompt Summary: ").append(promptSummary).append("\n");
			}
		}

		// Add exception type and message
		String exceptionType = latestLlmException.getClass().getSimpleName();
		String exceptionMessage = latestLlmException.getMessage();
		errorMessage.append("Latest error: [").append(exceptionType).append("] ").append(exceptionMessage);

		// Add exception count information
		if (!llmCallExceptions.isEmpty()) {
			errorMessage.append("\n(Total attempts: ").append(llmCallExceptions.size()).append(")");
		}

		// Add detailed error information for WebClientResponseException
		if (latestLlmException instanceof org.springframework.web.reactive.function.client.WebClientResponseException webClientException) {
			String responseBody = webClientException.getResponseBodyAsString();
			if (responseBody != null && !responseBody.isEmpty()) {
				errorMessage.append("\nAPI Response: ").append(responseBody);
			}
		}

		return errorMessage.toString();
	}

	/**
	 * Build a summary of the prompt messages (shows all messages as JSON without
	 * truncation) This includes tool calls, arguments, and all message fields for
	 * complete visibility
	 * @param messages List of messages in the prompt
	 * @return Full summary string with all messages as JSON
	 */
	private String buildPromptSummary(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return null;
		}

		StringBuilder summary = new StringBuilder();

		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);

			if (summary.length() > 0) {
				summary.append("\n");
			}

			// Serialize each message to JSON to show complete structure including tool
			// calls and arguments
			try {
				if (objectMapper != null) {
					String messageJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
					summary.append("Message ").append(i + 1).append(":\n").append(messageJson);
				}
				else {
					// Fallback if ObjectMapper is not available
					String messageType = message.getClass().getSimpleName();
					String messageText = message.getText();
					summary.append(messageType);
					if (messageText != null && !messageText.isEmpty()) {
						summary.append(": ").append(messageText);
					}
					else {
						summary.append(": <empty>");
					}
				}
			}
			catch (Exception e) {
				// Fallback if JSON serialization fails
				String messageType = message.getClass().getSimpleName();
				String messageText = message.getText();
				summary.append(messageType);
				if (messageText != null && !messageText.isEmpty()) {
					summary.append(": ").append(messageText);
				}
				else {
					summary.append(": <empty>");
				}
				summary.append(" (Failed to serialize to JSON: ").append(e.getMessage()).append(")");
			}
		}

		return summary.toString();
	}

	@Override
	protected CompletableFuture<AgentExecResult> act() {
		// Check for interruption before starting action process
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			log.info("Agent {} action process interrupted for rootPlanId: {}", getName(), getRootPlanId());
			return CompletableFuture
				.completedFuture(new AgentExecResult("Action interrupted by user", AgentState.INTERRUPTED));
		}

		try {
			if (agentStreamingResult == null) {
				return CompletableFuture.completedFuture(
						new AgentExecResult("Agent streaming result is null, please retry", AgentState.IN_PROGRESS));
			}

			if (!agentStreamingResult.hasToolCalls()) {
				return CompletableFuture
					.completedFuture(new AgentExecResult("tool call is empty, please retry", AgentState.IN_PROGRESS));
			}

			// Unified call to processTools() - chain the async result
			return processTools(agentStreamingResult.getToolCalls());
		}
		catch (Exception e) {
			log.error("Error executing tools: {}", e.getMessage(), e);

			StringBuilder errorMessage = new StringBuilder("Error executing tools: ");
			errorMessage.append(e.getMessage());

			String firstToolcall = actToolInfoList != null && !actToolInfoList.isEmpty()
					&& actToolInfoList.get(0).getParameters() != null
							? actToolInfoList.get(0).getParameters().toString() : "unknown";
			errorMessage.append("  . llm return param :  ").append(firstToolcall);

			// Clean up form input tool using root plan ID on error
			String rootPlanId = getRootPlanId();
			if (rootPlanId != null) {
				userInputService.removeFormInputTool(rootPlanId);
			}
			return CompletableFuture.completedFuture(new AgentExecResult(e.getMessage(), AgentState.COMPLETED));
		}
	}

	/**
	 * Unified method to process tools (single or multiple) Uses "build task list first,
	 * then execute" pattern
	 * @param toolCalls List of tool calls to execute
	 * @return CompletableFuture that completes with AgentExecResult containing the
	 * execution result
	 */
	private CompletableFuture<AgentExecResult> processTools(List<ToolCall> toolCalls) {
		// Check for interruption
		if (agentInterruptionHelper != null && !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
			return CompletableFuture
				.completedFuture(new AgentExecResult("Action interrupted by user", AgentState.INTERRUPTED));
		}

		// Validate that actToolInfoList size matches toolCalls size
		if (actToolInfoList.size() != toolCalls.size()) {
			String errorMessage = String.format(
					"Size mismatch: actToolInfoList has %d items but toolCalls has %d items. "
							+ "This indicates an inconsistency in tool call tracking.",
					actToolInfoList.size(), toolCalls.size());
			log.error(errorMessage);
			return CompletableFuture.completedFuture(new AgentExecResult(errorMessage, AgentState.IN_PROGRESS));
		}

		// Check if ParallelExecutionService is available
		if (parallelExecutionService == null) {
			log.error("ParallelExecutionService is not available");
			return CompletableFuture.completedFuture(
					new AgentExecResult("Parallel execution service is not available", AgentState.COMPLETED));
		}

		// 1. Build execution task list
		List<ExecutionTask> executionTasks = buildExecutionTasks(toolCalls);

		// 2. Detect if FormInputTool is present
		boolean hasFormInputTool = executionTasks.stream().anyMatch(ExecutionTask::isFormInputTool);

		// 3. Unified execution - chain async execution with result processing
		CompletableFuture<List<Map<String, Object>>> executionResultsFuture;
		if (hasFormInputTool) {
			// Contains FormInputTool: execute all sequentially
			executionResultsFuture = executeTasksSequentially(executionTasks);
		}
		else {
			// No FormInputTool: can execute in parallel
			executionResultsFuture = executeTasksInParallel(executionTasks);
		}

		// 4. Unified result processing - chain the result processing
		return executionResultsFuture.thenApply(executionResults -> {
			try {
				return processExecutionResults(executionTasks, executionResults);
			}
			catch (Exception e) {
				log.error("Error processing execution results: {}", e.getMessage(), e);
				return new AgentExecResult("Error processing execution results: " + e.getMessage(),
						AgentState.IN_PROGRESS);
			}
		}).exceptionally(e -> {
			log.error("Error executing tools: {}", e.getMessage(), e);
			return new AgentExecResult("Error executing tools: " + e.getMessage(), AgentState.IN_PROGRESS);
		});
	}

	/**
	 * Internal class to represent a tool execution task
	 */
	private static class ExecutionTask {

		final ToolCall toolCall;

		final ActToolParam param;

		final ToolCallBackContext toolCallBackContext;

		final int index; // Original index for maintaining order

		ExecutionTask(ToolCall toolCall, ActToolParam param, ToolCallBackContext toolCallBackContext, int index) {
			this.toolCall = toolCall;
			this.param = param;
			this.toolCallBackContext = toolCallBackContext;
			this.index = index;
		}

		boolean isFormInputTool() {
			return toolCallBackContext != null && toolCallBackContext.getFunctionInstance() instanceof FormInputTool;
		}

		boolean isTerminableTool() {
			return toolCallBackContext != null && toolCallBackContext.getFunctionInstance() instanceof TerminableTool;
		}

	}

	/**
	 * Build execution tasks from tool calls
	 * @param toolCalls List of tool calls to execute
	 * @return List of execution tasks
	 */
	private List<ExecutionTask> buildExecutionTasks(List<ToolCall> toolCalls) {
		List<ExecutionTask> tasks = new ArrayList<>();

		for (int i = 0; i < toolCalls.size(); i++) {
			ToolCall toolCall = toolCalls.get(i);
			ActToolParam param = actToolInfoList.get(i);
			ToolCallBackContext context = getToolCallBackContext(toolCall.name());

			tasks.add(new ExecutionTask(toolCall, param, context, i));
		}

		return tasks;
	}

	/**
	 * Execute tasks sequentially (used when FormInputTool is present)
	 * @param tasks List of execution tasks
	 * @return CompletableFuture that completes with list of execution results
	 */
	private CompletableFuture<List<Map<String, Object>>> executeTasksSequentially(List<ExecutionTask> tasks) {
		Map<String, ToolCallBackContext> toolCallbackMap = toolCallbackProvider.getToolCallBackContext();

		// Create parent ToolContext
		Map<String, Object> parentContextMap = new HashMap<>();
		parentContextMap.put("planDepth", getPlanDepth());
		addRecursiveCallChainToContext(parentContextMap);
		ToolContext parentToolContext = new ToolContext(parentContextMap);

		// Start with an empty list CompletableFuture
		CompletableFuture<List<Map<String, Object>>> chain = CompletableFuture.completedFuture(new ArrayList<>());

		for (ExecutionTask task : tasks) {
			// Use final to ensure each iteration captures a different task
			final ExecutionTask currentTask = task;
			// Chain each task sequentially
			chain = chain.thenCompose(results -> {
				// Check for interruption
				if (agentInterruptionHelper != null
						&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
					// Add interrupted result
					Map<String, Object> interruptedResult = new HashMap<>();
					interruptedResult.put("index", currentTask.index);
					interruptedResult.put("status", "INTERRUPTED");
					interruptedResult.put("error", "Execution interrupted");
					results.add(interruptedResult);
					return CompletableFuture.completedFuture(results);
				}

				// Special handling for FormInputTool
				if (currentTask.isFormInputTool()) {
					return executeFormInputToolAsync(currentTask).thenApply(formResult -> {
						results.add(formResult);
						return results;
					});
				}
				else {
					// Use ParallelExecutionService to execute (unified interface even for
					// sequential)
					Map<String, Object> params = parseToolArguments(currentTask.toolCall.arguments());

					// Create tool-specific ToolContext
					Map<String, Object> toolContextMap = new HashMap<>();
					toolContextMap.putAll(parentToolContext.getContext());
					toolContextMap.put("toolcallId", currentTask.param.getToolCallId());
					ToolContext toolContext = new ToolContext(toolContextMap);

					return parallelExecutionService
						.executeTool(currentTask.toolCall.name(), params, toolCallbackMap, toolContext,
								currentTask.index)
						.thenApply(result -> {
							results.add(result);
							return results;
						});
				}
			});
		}

		// Sort by original index to maintain order
		return chain.thenApply(results -> {
			results.sort((a, b) -> {
				Integer indexA = (Integer) a.get("index");
				Integer indexB = (Integer) b.get("index");
				return Integer.compare(indexA != null ? indexA : 0, indexB != null ? indexB : 0);
			});
			return results;
		});
	}

	/**
	 * Execute tasks in parallel (used when FormInputTool is not present)
	 * @param tasks List of execution tasks
	 * @return CompletableFuture that completes with list of execution results
	 */
	private CompletableFuture<List<Map<String, Object>>> executeTasksInParallel(List<ExecutionTask> tasks) {
		// Build ParallelExecutionRequest list
		List<ParallelExecutionService.ParallelExecutionRequest> executions = new ArrayList<>();

		for (ExecutionTask task : tasks) {
			Map<String, Object> params = parseToolArguments(task.toolCall.arguments());
			executions.add(new ParallelExecutionService.ParallelExecutionRequest(task.toolCall.name(), params,
					task.param.getToolCallId()));
		}

		// Create parent ToolContext
		Map<String, Object> parentContextMap = new HashMap<>();
		parentContextMap.put("planDepth", getPlanDepth());
		addRecursiveCallChainToContext(parentContextMap);
		ToolContext parentToolContext = new ToolContext(parentContextMap);

		// Use ParallelExecutionService to execute in parallel
		Map<String, ToolCallBackContext> toolCallbackMap = toolCallbackProvider.getToolCallBackContext();

		return parallelExecutionService.executeToolsInParallel(executions, toolCallbackMap, parentToolContext);
	}

	/**
	 * Execute FormInputTool with special handling (async version). This method
	 * asynchronously handles form input waiting without blocking business threads.
	 * @param task Execution task containing FormInputTool
	 * @return CompletableFuture that completes with execution result in unified format
	 */
	private CompletableFuture<Map<String, Object>> executeFormInputToolAsync(ExecutionTask task) {
		FormInputTool formInputTool = (FormInputTool) task.toolCallBackContext.getFunctionInstance();

		try {
			// Parse tool arguments to UserFormInput object
			FormInputTool.UserFormInput formInput;
			if (task.toolCall.arguments() != null && !task.toolCall.arguments().trim().isEmpty()) {
				formInput = objectMapper.readValue(task.toolCall.arguments(), FormInputTool.UserFormInput.class);
			}
			else {
				log.warn("FormInputTool called with empty arguments, creating empty form");
				formInput = new FormInputTool.UserFormInput();
			}

			// Call run() first to set the state to AWAITING_USER_INPUT
			// This is necessary because FormInputTool is a singleton and may have a stale
			// state
			formInputTool.run(formInput);

			// Asynchronously handle the form input tool logic
			return handleFormInputToolAsync(formInputTool, task.param).thenApply(formResult -> {
				// Convert to unified result format
				Map<String, Object> result = new HashMap<>();
				result.put("index", task.index);
				result.put("status", "SUCCESS");
				result.put("output", formResult.getResult());
				result.put("agentState", formResult.getState().name());
				return result;
			});
		}
		catch (Exception e) {
			log.error("Error executing FormInputTool: {}", e.getMessage(), e);
			Map<String, Object> errorResult = new HashMap<>();
			errorResult.put("index", task.index);
			errorResult.put("status", "ERROR");
			errorResult.put("error", "Error executing FormInputTool: " + e.getMessage());
			return CompletableFuture.completedFuture(errorResult);
		}
	}

	/**
	 * Process execution results and build memory
	 * @param tasks List of execution tasks
	 * @param executionResults List of execution results
	 * @return AgentExecResult containing the final result
	 */
	private AgentExecResult processExecutionResults(List<ExecutionTask> tasks,
			List<Map<String, Object>> executionResults) {

		// Validate result count
		if (executionResults.size() != tasks.size()) {
			String errorMsg = String.format("Result count mismatch: expected %d, got %d", tasks.size(),
					executionResults.size());
			log.error(errorMsg);
			return new AgentExecResult(errorMsg, AgentState.IN_PROGRESS);
		}

		// Process each result
		List<String> resultList = new ArrayList<>();
		List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
		boolean shouldTerminate = false;

		for (int i = 0; i < tasks.size(); i++) {
			ExecutionTask task = tasks.get(i);
			Map<String, Object> result = executionResults.get(i);

			// Extract result
			String status = (String) result.get("status");
			String processedResult;

			if ("SUCCESS".equals(status)) {
				Object outputObj = result.get("output");
				processedResult = (outputObj != null) ? processToolResult(outputObj.toString()) : "No output";
			}
			else {
				Object errorObj = result.get("error");
				processedResult = "Error: " + (errorObj != null ? errorObj.toString() : "Unknown error");
			}

			// Update ActToolParam
			task.param.setResult(processedResult);

			// Check termination condition
			if (task.isTerminableTool() && task.toolCallBackContext != null) {
				TerminableTool terminableTool = (TerminableTool) task.toolCallBackContext.getFunctionInstance();
				if (terminableTool.canTerminate()) {
					shouldTerminate = true;
					String rootPlanId = getRootPlanId();
					if (rootPlanId != null) {
						userInputService.removeFormInputTool(rootPlanId);
					}
				}
			}

			// Handle error reporting tools
			if (task.toolCallBackContext != null) {
				ToolCallBiFunctionDef<?> toolInstance = task.toolCallBackContext.getFunctionInstance();
				if (toolInstance instanceof ErrorReportTool) {
					String errorMessage = extractAndSetErrorMessage(processedResult, "ErrorReportTool");
					recordErrorToolThinkingAndAction(task.param, "Error occurred during execution",
							"ErrorReportTool called to report error", errorMessage);
				}
				else if (toolInstance instanceof SystemErrorReportTool) {
					String errorMessage = extractAndSetErrorMessage(processedResult, "SystemErrorReportTool");
					recordErrorToolThinkingAndAction(task.param, "System error occurred during execution",
							"SystemErrorReportTool called to report system error", errorMessage);
				}
			}

			// Build result list
			resultList.add(processedResult);
			toolResponses
				.add(new ToolResponseMessage.ToolResponse(task.toolCall.id(), task.toolCall.name(), processedResult));

			// Check for repeated results (only for single result)
			if (tasks.size() == 1) {
				checkAndHandleRepeatedResult(processedResult);
			}
		}

		// Record results
		recordActionResult(actToolInfoList);

		// Build Memory
		buildAndProcessMemory(toolResponses);

		// Return result
		String finalResult = tasks.size() == 1 ? resultList.get(0) : resultList.toString();

		return new AgentExecResult(finalResult, shouldTerminate ? AgentState.COMPLETED : AgentState.IN_PROGRESS);
	}

	/**
	 * Build and process memory from tool responses
	 * @param toolResponses List of tool response messages
	 */
	private void buildAndProcessMemory(List<ToolResponseMessage.ToolResponse> toolResponses) {
		ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(toolResponses).build();

		// Get AssistantMessage from agentStreamingResult if available, otherwise fall
		// back to response
		AssistantMessage assistantMessage;
		if (agentStreamingResult != null && agentStreamingResult.hasToolCalls()) {
			assistantMessage = agentStreamingResult.createAssistantMessage();
		}
		else {
			assistantMessage = extractAssistantMessageFromResponse(response);
		}

		// Build conversationHistory
		List<Message> conversationHistory = new ArrayList<>();
		conversationHistory.addAll(agentMessages);
		conversationHistory.add(assistantMessage);
		conversationHistory.add(toolResponseMessage);

		// Build ToolExecutionResult
		ToolExecutionResult toolExecutionResult = ToolExecutionResult.builder()
			.conversationHistory(conversationHistory)
			.returnDirect(false)
			.build();

		// Update memory
		processMemory(toolExecutionResult);
	}

	/**
	 * Extract AssistantMessage from ChatResponse
	 * @param response The ChatResponse containing the assistant message
	 * @return AssistantMessage with tool calls
	 */
	private AssistantMessage extractAssistantMessageFromResponse(ChatResponse response) {
		// Find the generation with tool calls
		Generation generation = response.getResults()
			.stream()
			.filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No tool calls found in response"));

		return generation.getOutput();
	}

	/**
	 * Parse tool arguments from JSON string to Map This method extracts valid JSON from
	 * the arguments string, removing any descriptive text that the LLM might have
	 * included.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> parseToolArguments(String arguments) {
		if (arguments == null || arguments.trim().isEmpty()) {
			return new HashMap<>();
		}

		// Try to extract valid JSON from the arguments string
		String cleanedArguments = extractJsonFromString(arguments);

		try {
			// Try to parse as JSON
			Object parsed = objectMapper.readValue(cleanedArguments, Object.class);
			if (parsed instanceof Map) {
				return (Map<String, Object>) parsed;
			}
			else {
				// If it's not a Map, wrap it
				Map<String, Object> result = new HashMap<>();
				result.put("value", parsed);
				return result;
			}
		}
		catch (Exception e) {
			// Try to fix common JSON issues (like unescaped newlines in string values)
			try {
				String fixedJson = fixJsonString(cleanedArguments);
				Object parsed = objectMapper.readValue(fixedJson, Object.class);
				if (parsed instanceof Map) {
					return (Map<String, Object>) parsed;
				}
				else {
					Map<String, Object> result = new HashMap<>();
					result.put("value", parsed);
					return result;
				}
			}
			catch (Exception e2) {
				log.warn("Failed to parse tool arguments as JSON: {}. Using empty map.", arguments, e);
				return new HashMap<>();
			}
		}
	}

	/**
	 * Extract valid JSON from a string that may contain descriptive text. This method
	 * finds the first valid JSON object or array in the string.
	 * @param input The input string that may contain descriptive text and JSON
	 * @return The extracted JSON string, or the original string if no JSON is found
	 */
	private String extractJsonFromString(String input) {
		if (input == null || input.trim().isEmpty()) {
			return input;
		}

		String trimmed = input.trim();

		// First, try to parse the entire string as JSON
		try {
			objectMapper.readTree(trimmed);
			return trimmed;
		}
		catch (Exception e) {
			// Not valid JSON, continue to extraction
		}

		// Try to find JSON object boundaries
		int startIndex = findJsonStart(trimmed);
		if (startIndex == -1) {
			// No JSON found, return original
			return trimmed;
		}

		int endIndex = findJsonEnd(trimmed, startIndex);
		if (endIndex == -1) {
			// No valid end found, return original
			return trimmed;
		}

		String extracted = trimmed.substring(startIndex, endIndex + 1);

		// Validate the extracted JSON
		try {
			objectMapper.readTree(extracted);
			return extracted;
		}
		catch (Exception e) {
			// Extracted string is not valid JSON, return original
			return trimmed;
		}
	}

	/**
	 * Find the start index of a JSON object or array in the string.
	 * @param input The input string
	 * @return The index of '{' or '[', or -1 if not found
	 */
	private int findJsonStart(String input) {
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '{' || c == '[') {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Find the end index of a JSON object or array, handling nested structures.
	 * @param input The input string
	 * @param startIndex The start index of the JSON structure
	 * @return The index of the matching closing brace/bracket, or -1 if not found
	 */
	private int findJsonEnd(String input, int startIndex) {
		char startChar = input.charAt(startIndex);
		char endChar = (startChar == '{') ? '}' : ']';

		int depth = 1;
		boolean inString = false;
		boolean escaped = false;

		for (int i = startIndex + 1; i < input.length(); i++) {
			char c = input.charAt(i);

			if (escaped) {
				escaped = false;
				continue;
			}

			if (c == '\\') {
				escaped = true;
				continue;
			}

			if (c == '"') {
				inString = !inString;
				continue;
			}

			if (inString) {
				continue;
			}

			if (c == startChar) {
				depth++;
			}
			else if (c == endChar) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}

		return -1;
	}

	/**
	 * Fix incorrectly escaped key-value pairs in JSON. Fixes the pattern "key\":\"value"
	 * to "key":"value"
	 * @param json The JSON string that may contain incorrectly escaped key-value pairs
	 * @return Fixed JSON string
	 */
	private String fixIncorrectlyEscapedKeyValuePairs(String json) {
		if (json == null || json.isEmpty()) {
			return json;
		}

		// Pattern to fix: "key\":\"value" -> "key":"value"
		// This happens when quotes around key-value pairs are incorrectly escaped
		// We need to find patterns like: " followed by \" followed by : followed by \"
		// This indicates an incorrectly escaped key-value separator
		// We'll use a targeted replacement that checks context to ensure it's a key-value
		// boundary

		StringBuilder fixed = new StringBuilder();
		int i = 0;
		while (i < json.length()) {
			// Look for the pattern: " followed by \" followed by : followed by \"
			// This is the pattern ":\" which indicates incorrectly escaped key-value
			// separator
			if (i + 5 < json.length() && json.charAt(i) == '"' && json.charAt(i + 1) == '\\'
					&& json.charAt(i + 2) == '"' && json.charAt(i + 3) == ':' && json.charAt(i + 4) == '\\'
					&& json.charAt(i + 5) == '"') {
				// Found the pattern ":\" - this is an incorrectly escaped key-value
				// separator
				// Check context to ensure this is indeed a key-value separator
				// Look backwards to see if we're after a key name
				boolean isValidContext = false;
				if (i > 0) {
					// Look backwards skipping whitespace to find the start of the key
					int lookBack = i - 1;
					while (lookBack >= 0 && Character.isWhitespace(json.charAt(lookBack))) {
						lookBack--;
					}
					if (lookBack >= 0) {
						char charBeforeSpace = json.charAt(lookBack);
						// If we're after a quote (end of key name), comma, or opening
						// brace, it's valid
						if (charBeforeSpace == '"' || charBeforeSpace == ',' || charBeforeSpace == '{'
								|| charBeforeSpace == '[') {
							isValidContext = true;
						}
					}
				}
				else {
					// At the start, could be valid if it's the first key
					isValidContext = true;
				}

				if (isValidContext) {
					// Fix: ":\" -> ":"
					fixed.append('"');
					fixed.append(':');
					fixed.append('"');
					i += 6; // Skip the 6 characters we just processed ("\":\")
					continue;
				}
			}

			// Not the pattern we're looking for, append character as-is
			fixed.append(json.charAt(i));
			i++;
		}

		return fixed.toString();
	}

	/**
	 * Fix common JSON formatting issues, such as unescaped newlines, quotes, and other
	 * special characters in string values. This method properly escapes all characters
	 * that need to be escaped inside JSON string values.
	 * @param json The JSON string that may contain formatting issues
	 * @return Fixed JSON string with properly escaped characters
	 */
	private String fixJsonString(String json) {
		if (json == null || json.isEmpty()) {
			return json;
		}

		// First, fix the pattern "key\":\"value" -> "key":"value"
		// This pattern occurs when quotes around key-value pairs are incorrectly escaped
		json = fixIncorrectlyEscapedKeyValuePairs(json);

		StringBuilder fixed = new StringBuilder();
		boolean inString = false;
		boolean escaped = false;

		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);

			if (escaped) {
				// We're in an escape sequence, just append the character
				fixed.append(c);
				escaped = false;
				continue;
			}

			if (c == '\\') {
				// Start of escape sequence
				fixed.append(c);
				escaped = true;
				continue;
			}

			if (c == '"') {
				if (inString) {
					// We're inside a string, check if this is a valid string terminator
					// Look ahead to see if this quote is followed by valid JSON structure
					// Skip whitespace and check for comma, colon, closing brace/bracket
					boolean isValidTerminator = false;
					// Skip whitespace after the quote
					int j = i + 1;
					while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
						j++;
					}
					if (j < json.length()) {
						char nextChar = json.charAt(j);
						if (nextChar == ',' || nextChar == ':' || nextChar == '}' || nextChar == ']') {
							// This looks like a valid string terminator
							isValidTerminator = true;
						}
					}
					else {
						// End of string, must be terminator
						isValidTerminator = true;
					}

					if (isValidTerminator) {
						// Valid string terminator
						inString = false;
						fixed.append(c);
					}
					else {
						// Unescaped quote inside string value - escape it
						fixed.append("\\\"");
					}
				}
				else {
					// Start of string
					inString = true;
					fixed.append(c);
				}
				continue;
			}

			if (inString) {
				// Inside a string value, escape special characters
				if (c == '\n') {
					fixed.append("\\n");
				}
				else if (c == '\r') {
					fixed.append("\\r");
				}
				else if (c == '\t') {
					fixed.append("\\t");
				}
				else if (c == '\b') {
					fixed.append("\\b");
				}
				else if (c == '\f') {
					fixed.append("\\f");
				}
				else if (c < 32) {
					// Other control characters (ASCII < 32)
					fixed.append(String.format("\\u%04x", (int) c));
				}
				else {
					// Regular character, append as-is
					fixed.append(c);
				}
			}
			else {
				// Outside string, append as-is
				fixed.append(c);
			}
		}

		return fixed.toString();
	}

	/**
	 * Handle FormInputTool specific logic with exclusive storage (async version). This
	 * method asynchronously waits for user input without blocking business threads.
	 */
	private CompletableFuture<AgentExecResult> handleFormInputToolAsync(FormInputTool formInputTool,
			ActToolParam param) {
		// Ensure the form input tool has the correct plan IDs set
		formInputTool.setCurrentPlanId(getCurrentPlanId());
		formInputTool.setRootPlanId(getRootPlanId());

		// Check if the tool is waiting for user input
		if (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
			String rootPlanId = getRootPlanId();
			String currentPlanId = getCurrentPlanId();
			log.info("FormInputTool is awaiting user input for rootPlanId: {} (currentPlanId: {})", rootPlanId,
					currentPlanId);

			// Use exclusive storage method - this will handle waiting and queuing
			// automatically
			boolean stored = userInputService.storeFormInputToolExclusive(rootPlanId, formInputTool, currentPlanId);
			if (!stored) {
				log.error("Failed to store form for sub-plan {} due to lock timeout or interruption", currentPlanId);
				param.setResult("Failed to store form due to system timeout");
				return CompletableFuture.completedFuture(
						new AgentExecResult("Failed to store form due to system timeout", AgentState.COMPLETED));
			}

			// Asynchronously wait for user input or timeout
			return waitForUserInputOrTimeoutAsync(formInputTool).thenApply(v -> {
				// After waiting, check the state again
				if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
					log.info("User input received for rootPlanId: {} from sub-plan {}", rootPlanId, currentPlanId);

					ToolStateInfo stateInfo = formInputTool.getCurrentToolStateString();
					UserMessage userMessage = UserMessage.builder()
						.text("User input received for form: " + (stateInfo != null ? stateInfo.getStateString() : ""))
						.build();
					processUserInputToMemory(userMessage);

					// Update the result in actToolInfoList
					param.setResult(stateInfo != null ? stateInfo.getStateString() : "");
					return new AgentExecResult(param.getResult(), AgentState.IN_PROGRESS);

				}
				else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
					log.warn("Input timeout occurred for FormInputTool for rootPlanId: {} from sub-plan {}", rootPlanId,
							currentPlanId);

					UserMessage userMessage = UserMessage.builder().text("Input timeout occurred for form: ").build();
					processUserInputToMemory(userMessage);
					// Don't remove FormInputTool immediately on timeout - allow late
					// submissions
					// The tool will be cleaned up when the plan execution completes or
					// when explicitly removed
					// userInputService.removeFormInputTool(rootPlanId);
					param.setResult("Input timeout occurred");

					return new AgentExecResult("Input timeout occurred.", AgentState.IN_PROGRESS);
				}
				else {
					throw new RuntimeException("FormInputTool is not in the correct state");
				}
			});
		}
		else {
			return CompletableFuture.completedFuture(
					new AgentExecResult("FormInputTool is not in AWAITING_USER_INPUT state", AgentState.FAILED));
		}
	}

	/**
	 * Recursively convert HashMap to LinkedHashMap to preserve insertion order
	 * @param obj The object that may contain HashMaps
	 * @return Object with all HashMaps converted to LinkedHashMaps
	 */
	private Object convertToLinkedHashMap(Object obj) {
		if (obj == null) {
			return null;
		}

		if (obj instanceof Map<?, ?> map) {
			// Convert HashMap to LinkedHashMap
			Map<String, Object> linkedMap = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String key) {
					linkedMap.put(key, convertToLinkedHashMap(entry.getValue()));
				}
			}
			return linkedMap;
		}
		else if (obj instanceof List<?> list) {
			// Recursively process list items
			List<Object> linkedList = new ArrayList<>();
			for (Object item : list) {
				linkedList.add(convertToLinkedHashMap(item));
			}
			return linkedList;
		}

		return obj;
	}

	/**
	 * Process tool result to remove escaped JSON if it's a valid JSON string. This fixes
	 * the issue where DefaultToolCallingManager returns escaped JSON strings.
	 * @param result The raw tool result string
	 * @return Processed result with unescaped JSON if applicable
	 */
	private String processToolResult(String result) {
		return result;
	}

	/**
	 * Record action result with simplified parameters
	 */
	private void recordActionResult(List<ActToolParam> actToolInfoList) {
		planExecutionRecorder.recordActionResult(actToolInfoList);
	}

	/**
	 * Extract error message from tool result and set it on the step
	 * @param result The tool result JSON string
	 * @param toolName The name of the tool (for logging)
	 * @return The extracted error message, or the result itself if extraction fails
	 */
	private String extractAndSetErrorMessage(String result, String toolName) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> errorData = objectMapper.readValue(result, Map.class);
			String errorMessage = (String) errorData.get("errorMessage");
			if (errorMessage != null && !errorMessage.isEmpty()) {
				step.setErrorMessage(errorMessage);
				log.info("{} extracted errorMessage for stepId: {}, errorMessage: {}", toolName, step.getStepId(),
						errorMessage);
				return errorMessage;
			}
		}
		catch (Exception e) {
			log.warn("Failed to parse errorMessage from {} result: {}", toolName, result, e);
		}
		// Fallback: use the result as errorMessage
		step.setErrorMessage(result);
		return result;
	}

	/**
	 * Record thinking and action for error reporting tools to make them visible in
	 * frontend
	 * @param param The ActToolParam containing tool information
	 * @param thinkInput Description of the error context
	 * @param thinkOutput Description of what tool was called
	 * @param errorMessage The actual error message
	 */
	private void recordErrorToolThinkingAndAction(ActToolParam param, String thinkInput, String thinkOutput,
			String errorMessage) {
		try {
			String stepId = step.getStepId();
			String thinkActId = planIdDispatcher.generateThinkActId();
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;

			// Try to get model context limit if available, otherwise use null
			Integer errorModelContextLimit = currentModelContextLimit;
			if (errorModelContextLimit == null && llmService != null && llmService.getTokenLimitService() != null) {
				String effectiveModelName = (modelName != null && !modelName.isEmpty()) ? modelName
						: llmService.getDefaultModelName();
				if (effectiveModelName != null && !effectiveModelName.trim().isEmpty()) {
					try {
						errorModelContextLimit = llmService.getTokenLimitService().getContextLimit(effectiveModelName);
					}
					catch (Exception e) {
						log.debug("Could not get model context limit for error record: {}", e.getMessage());
					}
				}
			}

			ThinkActRecordParams errorParams = new ThinkActRecordParams(thinkActId, stepId, thinkInput, thinkOutput,
					finalErrorMessage, null, null, errorModelContextLimit, List.of(param));
			planExecutionRecorder.recordThinkingAndAction(step, errorParams);
			log.info("Recorded thinking and action for error tool, stepId: {}", stepId);
		}
		catch (Exception e) {
			log.warn("Failed to record thinking and action for error tool", e);
		}
	}

	/**
	 * Handle LLM timeout (3 retries exhausted) by simulating full flow with
	 * SystemErrorReportTool
	 * @return AgentExecResult with error information
	 */
	private AgentExecResult handleLlmTimeoutWithSystemErrorReport() {
		log.error("Handling LLM timeout with SystemErrorReportTool");

		try {
			// Create SystemErrorReportTool instance
			SystemErrorReportTool errorTool = new SystemErrorReportTool(getCurrentPlanId(), objectMapper);

			// Build error message from latest exception
			String errorMessage = buildErrorMessageFromLatestException("think()");

			// Build structured error input with context
			Map<String, Object> errorInput = new HashMap<>();
			errorInput.put("errorMessage", errorMessage);

			// Add context fields
			errorInput.put("functionName", "think()");
			if (agentName != null && !agentName.isEmpty()) {
				errorInput.put("agentName", agentName);
			}
			errorInput.put("stepNumber", getCurrentStep());

			String effectiveModelName = modelName;
			if (effectiveModelName == null || effectiveModelName.isEmpty()) {
				effectiveModelName = llmService != null ? llmService.getDefaultModelName() : null;
			}
			if (effectiveModelName != null && !effectiveModelName.isEmpty()) {
				errorInput.put("modelName", effectiveModelName);
			}

			// Add input token count if available
			int inputTokenCount = -1;
			if (agentStreamingResult != null && agentStreamingResult.getInputTokenCount() > 0) {
				inputTokenCount = agentStreamingResult.getInputTokenCount();
			}
			else if (streamResult != null && streamResult.getInputTokenCount() > 0) {
				inputTokenCount = streamResult.getInputTokenCount();
			}
			if (inputTokenCount > 0) {
				errorInput.put("inputTokenCount", inputTokenCount);
			}

			// Add prompt summary if available
			if (userPrompt != null && userPrompt.getInstructions() != null && !userPrompt.getInstructions().isEmpty()) {
				String promptSummary = buildPromptSummary(userPrompt.getInstructions());
				if (promptSummary != null && !promptSummary.isEmpty()) {
					errorInput.put("promptSummary", promptSummary);
				}
			}

			// Execute the error report tool
			ToolExecuteResult toolResult = errorTool.run(errorInput);

			// Simulate post-tool flow (memory processing, recording, etc.)
			String result = simulatePostToolFlow(errorTool, toolResult, errorMessage);

			// Extract error message for step
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> errorData = objectMapper.readValue(toolResult.getOutput(), Map.class);
				String extractedErrorMessage = (String) errorData.get("errorMessage");
				if (extractedErrorMessage != null && !extractedErrorMessage.isEmpty()) {
					step.setErrorMessage(extractedErrorMessage);
				}
			}
			catch (Exception e) {
				log.warn("Failed to parse errorMessage from SystemErrorReportTool result", e);
				step.setErrorMessage(errorMessage);
			}

			// Record thinking and action for SystemErrorReportTool to make it visible in
			// frontend
			String toolCallId = planIdDispatcher.generateToolCallId();
			String parametersJson = objectMapper.writeValueAsString(errorInput);
			ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson, toolResult.getOutput(),
					toolCallId);
			String finalErrorMessage = step.getErrorMessage() != null ? step.getErrorMessage() : errorMessage;
			recordErrorToolThinkingAndAction(param, "LLM timeout after 3 retries",
					"SystemErrorReportTool called to report LLM timeout error", finalErrorMessage);

			return new AgentExecResult(result, AgentState.FAILED);
		}
		catch (Exception e) {
			log.error("Failed to handle LLM timeout with SystemErrorReportTool", e);
			String fallbackError = "LLM timeout error: " + buildErrorMessageFromLatestException("think()");
			step.setErrorMessage(fallbackError);
			return new AgentExecResult(fallbackError, AgentState.FAILED);
		}
	}

	@Override
	protected String simulatePostToolFlow(Object tool, ToolExecuteResult toolResult, String errorMessage) {
		// Override to provide DynamicAgent-specific post-tool flow
		// This simulates what normally happens after tool execution:
		// 1. Process memory (if applicable)
		// 2. Record action result

		// For SystemErrorReportTool, we need to create a mock ActToolParam for recording
		if (tool instanceof SystemErrorReportTool) {
			try {
				String toolCallId = planIdDispatcher.generateToolCallId();
				String parametersJson = objectMapper.writeValueAsString(Map.of("errorMessage", errorMessage));
				ActToolParam param = new ActToolParam(SystemErrorReportTool.name, parametersJson,
						toolResult.getOutput(), toolCallId);

				// Record the action result
				recordActionResult(List.of(param));
			}
			catch (Exception e) {
				log.warn("Failed to record SystemErrorReportTool result", e);
			}
		}

		return toolResult.getOutput();
	}

	/**
	 * Check if the tool result is repeating and force compress memory if detected This
	 * helps break potential loops where the agent keeps getting the same result
	 * @param result The tool execution result to check
	 */
	private void checkAndHandleRepeatedResult(String result) {
		if (result == null || result.trim().isEmpty()) {
			return;
		}

		// Add to recent results list without normalization
		recentToolResults.add(result);

		// Keep only the last REPEATED_RESULT_THRESHOLD results
		if (recentToolResults.size() > REPEATED_RESULT_THRESHOLD) {
			recentToolResults.remove(0);
		}

		// Check if we have enough samples and if they're all the same
		if (recentToolResults.size() >= REPEATED_RESULT_THRESHOLD) {
			boolean allSame = true;
			String firstResult = recentToolResults.get(0);

			for (int i = 1; i < recentToolResults.size(); i++) {
				if (!firstResult.equals(recentToolResults.get(i))) {
					allSame = false;
					break;
				}
			}

			if (allSame) {
				log.warn(
						"🔁 Detected repeated tool result {} times for planId: {}. Forcing memory compression to break potential loop.",
						REPEATED_RESULT_THRESHOLD, getCurrentPlanId());

				// Force compress agent memory to break the loop
				if (conversationMemoryLimitService != null) {
					agentMessages = conversationMemoryLimitService.forceCompressAgentMemory(agentMessages);
				}

				// Clear the recent results after compression
				recentToolResults.clear();
				log.info("✅ Forced memory compression completed for planId: {}", getCurrentPlanId());
			}
		}
	}

	private void processUserInputToMemory(UserMessage userMessage) {
		if (userMessage != null && userMessage.getText() != null) {
			// Process the user message to update memory
			String userInput = userMessage.getText();

			if (!StringUtils.isBlank(userInput)) {
				// Add user input to memory
				agentMessages.add(userMessage);
			}
		}
	}

	/**
	 * Check and compress memory if needed based on the full prompt token count. This
	 * method calculates the token count of the complete prompt (systemMessage +
	 * agentMessages + currentStepEnvMessage), compresses agentMessages if needed, checks
	 * against model context limit, and returns the final input token count.
	 * @param systemMessage System message
	 * @param currentStepEnvMessage Current step environment message
	 * @return The input token count of the final prompt (after compression if needed)
	 * @throws TokenLimitExceededException if token count exceeds model context limit
	 * after compression
	 */
	private int checkAndCompressMemoryIfNeeded(Message systemMessage, Message currentStepEnvMessage) {
		// Build temporary prompt list to calculate total token count
		List<Message> tempMessages = new ArrayList<>();
		tempMessages.add(systemMessage);
		if (agentMessages != null && !agentMessages.isEmpty()) {
			tempMessages.addAll(agentMessages);
		}
		tempMessages.add(currentStepEnvMessage);

		// Get token services
		TokenCountService tokenCountService = llmService.getTokenCountService();
		TokenLimitService tokenLimitService = llmService.getTokenLimitService();

		// Calculate total token count using TokenCountService if available, otherwise use
		// ConversationMemoryLimitService
		int totalTokens;
		if (tokenCountService != null) {
			totalTokens = tokenCountService.countTokens(tempMessages);
		}
		else {
			if (conversationMemoryLimitService == null) {
				log.warn(
						"Neither TokenCountService nor ConversationMemoryLimitService is available. Cannot calculate token count.");
				// Return 0 as fallback - caller should handle this case
				return 0;
			}
			totalTokens = conversationMemoryLimitService.calculateTotalTokens(tempMessages);
		}

		// Get model context limit (use modelContextLimit only, no sessionTokenLimit)
		if (tokenLimitService == null) {
			throw new IllegalStateException(
					"TokenLimitService is not available. Cannot get token limit for memory compression.");
		}

		String effectiveModelName = (modelName != null && !modelName.isEmpty()) ? modelName
				: llmService.getDefaultModelName();
		if (effectiveModelName == null || effectiveModelName.trim().isEmpty()) {
			throw new IllegalStateException(
					"Model name is not available. Cannot get token limit for memory compression.");
		}

		int modelContextLimit = tokenLimitService.getContextLimit(effectiveModelName);
		// Store for later use when recording ThinkActRecord
		this.currentModelContextLimit = modelContextLimit;

		// Get compression threshold (default 70%)
		double compressionThreshold = lynxeProperties != null ? (lynxeProperties.getChatCompressionThreshold() != null
				? lynxeProperties.getChatCompressionThreshold() : 0.7) : 0.7;
		int thresholdTokens = (int) (modelContextLimit * compressionThreshold);

		// Only compress if exceeding threshold
		if (totalTokens <= thresholdTokens) {
			log.debug(
					"Full prompt token count ({} tokens) is within compression threshold ({} tokens, {}% of model limit {})",
					totalTokens, thresholdTokens, (int) (compressionThreshold * 100), modelContextLimit);
		}
		else {
			log.info(
					"Full prompt token count ({} tokens) exceeds compression threshold ({} tokens, {}% of model limit {}). Compressing agentMessages...",
					totalTokens, thresholdTokens, (int) (compressionThreshold * 100), modelContextLimit);

			// Compress agentMessages (which already contains extraMessage if first round)
			if (conversationMemoryLimitService != null && agentMessages != null && !agentMessages.isEmpty()) {
				try {
					agentMessages = conversationMemoryLimitService.forceCompressAgentMemory(agentMessages);

					// Rebuild temp prompt with compressed agentMessages and recalculate
					// token count
					tempMessages.clear();
					tempMessages.add(systemMessage);
					tempMessages.addAll(agentMessages);
					tempMessages.add(currentStepEnvMessage);

					// Recalculate token count after compression
					if (tokenCountService != null) {
						totalTokens = tokenCountService.countTokens(tempMessages);
					}
					else {
						totalTokens = conversationMemoryLimitService.calculateTotalTokens(tempMessages);
					}

					log.info(
							"Compression completed. Agent memory now contains {} messages. Final prompt token count: {}",
							agentMessages.size(), totalTokens);
				}
				catch (Exception e) {
					log.warn("Failed to compress memory", e);
					// Continue with original token count if compression fails
				}
			}
		}

		// Check token limit against model context limit (after compression)
		// Note: tokenLimitService and effectiveModelName are guaranteed to be non-null at
		// this point
		// Check if token count exceeds model context limit
		if (totalTokens > modelContextLimit) {
			String errorMessage = String.format("Token limit exceeded: current=%d, limit=%d, model=%s", totalTokens,
					modelContextLimit, effectiveModelName);
			log.error(errorMessage);

			// Last resort: Try aggressive compression one more time before throwing
			// exception
			if (conversationMemoryLimitService != null && agentMessages != null && !agentMessages.isEmpty()) {
				try {
					log.warn("Attempting aggressive compression as last resort. Current token count: {}, limit: {}",
							totalTokens, modelContextLimit);

					// Force compress agentMessages again with more aggressive settings
					List<Message> compressedMessages = conversationMemoryLimitService
						.forceCompressAgentMemory(agentMessages);

					// Rebuild temp prompt with aggressively compressed agentMessages
					tempMessages.clear();
					tempMessages.add(systemMessage);
					tempMessages.addAll(compressedMessages);
					tempMessages.add(currentStepEnvMessage);

					// Recalculate token count after aggressive compression
					if (tokenCountService != null) {
						totalTokens = tokenCountService.countTokens(tempMessages);
					}
					else {
						totalTokens = conversationMemoryLimitService.calculateTotalTokens(tempMessages);
					}

					// Update agentMessages with compressed version
					agentMessages = compressedMessages;

					log.info(
							"Aggressive compression completed. Agent memory now contains {} messages. Final prompt token count: {}",
							agentMessages.size(), totalTokens);

					// Check again if we're still over the limit after aggressive
					// compression
					if (totalTokens > modelContextLimit) {
						String finalErrorMessage = String
							.format("%s. Please reduce the input size or clear conversation history.", errorMessage);
						log.error("Token limit still exceeded after aggressive compression: {}", finalErrorMessage);
						throw new TokenLimitExceededException(totalTokens, modelContextLimit, effectiveModelName);
					}
					else {
						log.info("Aggressive compression succeeded. Token count reduced to {} (limit: {})", totalTokens,
								modelContextLimit);
					}
				}
				catch (TokenLimitExceededException e) {
					// Re-throw TokenLimitExceededException
					throw e;
				}
				catch (Exception e) {
					log.warn("Failed to perform aggressive compression as last resort", e);
					// Throw original exception if compression fails
					throw new TokenLimitExceededException(totalTokens, modelContextLimit, effectiveModelName);
				}
			}
			else {
				// No compression service available or no messages to compress
				String finalErrorMessage = String
					.format("%s. Please reduce the input size or clear conversation history.", errorMessage);
				log.error(finalErrorMessage);
				throw new TokenLimitExceededException(totalTokens, modelContextLimit, effectiveModelName);
			}
		}

		log.info("User prompt token count: {}", totalTokens);
		return totalTokens;
	}

	private void processMemory(ToolExecutionResult toolExecutionResult) {
		if (toolExecutionResult == null) {
			return;
		}

		// Process the tool execution result messages to update memory
		List<Message> messages = toolExecutionResult.conversationHistory();
		if (messages.isEmpty()) {
			return;
		}

		// Filter messages to keep only assistant message and tool_call message
		// Also preserve compression summary messages (UserMessages with special metadata)
		List<Message> messagesToAdd = new ArrayList<>();
		for (Message message : messages) {
			// exclude all system message
			if (message instanceof SystemMessage) {
				continue;
			}
			// exclude env data message, but preserve compression summary messages
			if (message instanceof UserMessage) {
				// Check if this is a compression summary message that should be preserved
				Object compressionSummaryFlag = message.getMetadata()
					.get(ConversationMemoryLimitService.COMPRESSION_SUMMARY_METADATA_KEY);
				if (compressionSummaryFlag != null && Boolean.TRUE.equals(compressionSummaryFlag)) {
					// This is a compression summary, preserve it in agent memory
					messagesToAdd.add(message);
					log.debug("Preserving compression summary message in agent memory for planId: {}",
							getCurrentPlanId());
				}
				// Other UserMessages are excluded (env data messages)
				continue;
			}
			// only keep assistant message and tool_call message
			messagesToAdd.add(message);
		}

		// Step 3: Clear current plan memory and add filtered messages to Agent Memory
		agentMessages.clear();
		agentMessages.addAll(messagesToAdd);
	}

	@Override
	public CompletableFuture<AgentExecResult> run() {
		return super.run();
	}

	@Override
	protected void handleFailedExecution(List<AgentExecResult> results) {
		log.info("Handling failed execution - performing cleanup");
		// Perform cleanup when execution fails (e.g., LLM timeout)
		String planId = getCurrentPlanId();
		if (planId != null) {
			try {
				clearUp(planId);
				log.info("Successfully cleaned up resources after failed execution for planId: {}", planId);
			}
			catch (Exception e) {
				log.error("Error during cleanup after failed execution for planId: {}", planId, e);
			}
		}
		super.handleFailedExecution(results);
	}

	@Override
	protected void handleInterruptedExecution(List<AgentExecResult> results) {
		log.info("Handling interrupted execution - performing cleanup");
		// Perform cleanup when execution is interrupted
		String planId = getCurrentPlanId();
		if (planId != null) {
			try {
				clearUp(planId);
				log.info("Successfully cleaned up resources after interrupted execution for planId: {}", planId);
			}
			catch (Exception e) {
				log.error("Error during cleanup after interrupted execution for planId: {}", planId, e);
			}
		}
		super.handleInterruptedExecution(results);
	}

	@Override
	protected void handleCompletedExecution(List<AgentExecResult> results) {
		super.handleCompletedExecution(results);
		// Note: Final result will be saved to conversation memory in
		// PlanFinalizer.handlePostExecution()
	}

	@Override
	protected String generateFinalSummary() {
		try {
			log.info("Generating final summary for agent execution");

			// Get all memory entries from agentMessages
			List<Message> memoryEntries = new ArrayList<>(agentMessages);

			if (memoryEntries.isEmpty()) {
				return "No memory entries found for final summary";
			}

			// Use LLM to generate a concise summary
			String summaryPrompt = """
					Based on the completed steps, try to answer the user's original request.
					If the current steps are insufficient to support answering the original request,
					simply describe that the step limit has been reached and please try again.

					""";
			// Create a simple prompt for summary generation
			UserMessage summaryRequest = new UserMessage(summaryPrompt);
			memoryEntries.add(getThinkMessage());
			memoryEntries.add(getNextStepWithEnvMessage());
			memoryEntries.add(summaryRequest);
			Prompt prompt = new Prompt(memoryEntries);

			// Get LLM response for summary
			ChatClient chatClient;
			if (modelName == null || modelName.isEmpty()) {
				chatClient = llmService.getDefaultDynamicAgentChatClient();
			}
			else {
				chatClient = llmService.getDynamicAgentChatClient(modelName);
			}
			ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

			String summary = response.getResult().getOutput().getText();
			log.info("Generated final summary: {}", summary);
			return summary;

		}
		catch (Exception e) {
			log.error("Failed to generate final summary", e);
			return "Summary generation failed: " + e.getMessage();
		}
	}

	@Override
	public String getName() {
		return this.agentName;
	}

	@Override
	public String getDescription() {
		return this.agentDescription;
	}

	@Override
	protected Message getNextStepWithEnvMessage() {
		if (StringUtils.isBlank(this.nextStepPrompt)) {
			return new UserMessage("");
		}
		PromptTemplate promptTemplate = new SystemPromptTemplate(this.nextStepPrompt);
		Message userMessage = promptTemplate.createMessage(getMergedData());
		return userMessage;
	}

	private Map<String, Object> getMergedData() {
		Map<String, Object> data = new HashMap<>();
		data.putAll(getInitSettingData());
		data.put(CURRENT_STEP_ENV_DATA_KEY, convertEnvDataToString());
		return data;
	}

	/**
	 * Add recursive call chain to ToolContext map if available in initSettings
	 * @param toolContextMap The ToolContext map to add the recursive call chain to
	 */
	@SuppressWarnings("unchecked")
	private void addRecursiveCallChainToContext(Map<String, Object> toolContextMap) {
		Map<String, Object> initSettings = getInitSettingData();
		Object chainObj = initSettings.get(AbstractPlanExecutor.RECURSIVE_CALL_CHAIN_KEY);
		if (chainObj instanceof List) {
			List<?> chain = (List<?>) chainObj;
			// Validate that all elements are strings
			boolean allStrings = chain.stream().allMatch(item -> item instanceof String);
			if (allStrings) {
				toolContextMap.put(SubplanToolWrapper.RECURSIVE_CALL_CHAIN_KEY, chain);
			}
		}
	}

	@Override
	protected Message getThinkMessage() {
		Message baseThinkPrompt = super.getThinkMessage();
		Message nextStepWithEnvMessage = getNextStepWithEnvMessage();
		UserMessage thinkMessage = new UserMessage("""
				%s
				%s
				""".formatted(baseThinkPrompt.getText(), nextStepWithEnvMessage.getText()));
		return thinkMessage;
	}

	/**
	 * Current step env data
	 * @return User message for current step environment data
	 */
	private Message currentStepEnvMessage() {
		String currentStepEnv = """
				- Current step environment information:
				{current_step_env_data}
				""";
		PromptTemplate template = new PromptTemplate(currentStepEnv);
		Message stepEnvMessage = template.createMessage(getMergedData());
		// mark as current step env data
		stepEnvMessage.getMetadata().put(CURRENT_STEP_ENV_DATA_KEY, Boolean.TRUE);
		return stepEnvMessage;
	}

	public ToolCallBackContext getToolCallBackContext(String toolKey) {
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		if (toolCallBackContext.containsKey(toolKey)) {
			return toolCallBackContext.get(toolKey);
		}
		else {
			log.warn("Tool callback for {} not found in the map.", toolKey);
			return null;
		}
	}

	@Override
	public List<ToolCallback> getToolCallList() {
		List<ToolCallback> toolCallbacks = new ArrayList<>();
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();
		for (String toolKey : availableToolKeys) {
			if (toolCallBackContext.containsKey(toolKey)) {
				ToolCallBackContext toolCallback = toolCallBackContext.get(toolKey);
				if (toolCallback != null) {
					toolCallbacks.add(toolCallback.getToolCallback());
				}
			}
			else {
				log.warn("Tool callback for {} not found in the map.", toolKey);
			}
		}
		return toolCallbacks;
	}

	public void addEnvData(String key, String value) {
		Map<String, Object> data = super.getInitSettingData();
		if (data == null) {
			throw new IllegalStateException("Data map is null. Cannot add environment data.");
		}
		data.put(key, value);
	}

	public void setToolCallbackProvider(ToolCallbackProvider toolCallbackProvider) {
		this.toolCallbackProvider = toolCallbackProvider;
	}

	protected ToolStateInfo collectEnvData(String toolCallName) {
		log.info("🔍 collectEnvData called for tool: {}", toolCallName);
		Map<String, ToolCallBackContext> toolCallBackContext = toolCallbackProvider.getToolCallBackContext();

		// Convert serviceGroup.toolName format to serviceGroup-toolName format if needed
		String lookupKey = toolCallName;
		try {
			String convertedKey = serviceGroupIndexService.constructFrontendToolKey(toolCallName);
			if (convertedKey != null && !convertedKey.equals(toolCallName)) {
				lookupKey = convertedKey;
				log.debug("Converted tool key from '{}' to '{}' for collectEnvData", toolCallName, lookupKey);
			}
		}
		catch (Exception e) {
			log.debug("Failed to convert tool key '{}' in collectEnvData: {}", toolCallName, e.getMessage());
		}

		ToolCallBackContext context = toolCallBackContext.get(lookupKey);
		if (context != null) {
			ToolCallBiFunctionDef<?> functionInstance = context.getFunctionInstance();
			// Use getCurrentToolStateStringWithErrorHandler which provides unified error
			// handling
			// This method is available as a default method in the interface
			ToolStateInfo envData = functionInstance.getCurrentToolStateStringWithErrorHandler();
			return envData != null ? envData : new ToolStateInfo(lookupKey, "");
		}
		// If corresponding tool callback context is not found, return empty ToolStateInfo
		log.warn("⚠️ No context found for tool: {} (lookup key: {})", toolCallName, lookupKey);
		return new ToolStateInfo(lookupKey, "");
	}

	public void collectAndSetEnvDataForTools() {

		Map<String, Object> toolEnvDataMap = new HashMap<>();
		Map<String, ToolStateInfo> deduplicatedStateMap = new HashMap<>();

		Map<String, Object> oldMap = getEnvData();
		toolEnvDataMap.putAll(oldMap);

		// Collect ToolStateInfo objects and deduplicate by key
		for (String toolKey : availableToolKeys) {
			ToolStateInfo stateInfo = collectEnvData(toolKey);
			if (stateInfo != null && stateInfo.getStateString() != null
					&& !stateInfo.getStateString().trim().isEmpty()) {
				String dedupKey = stateInfo.getKey();
				// Ignore ToolStateInfo with empty or null key
				if (dedupKey != null && !dedupKey.trim().isEmpty()) {
					// Deduplicate: if multiple tools have the same key, keep only the
					// first one
					if (!deduplicatedStateMap.containsKey(dedupKey)) {
						deduplicatedStateMap.put(dedupKey, stateInfo);
					}
				}
			}
			// Still store individual tool data for backward compatibility
			toolEnvDataMap.put(toolKey, stateInfo);
		}

		// Store deduplicated state map with a special key
		toolEnvDataMap.put("_deduplicated_states", deduplicatedStateMap);
		// log.debug("Collected tool environment data: {}", toolEnvDataMap);

		setEnvData(toolEnvDataMap);
	}

	public String convertEnvDataToString() {
		StringBuilder envDataStringBuilder = new StringBuilder();

		// Use deduplicated states if available
		Map<String, Object> envData = getEnvData();
		@SuppressWarnings("unchecked")
		Map<String, ToolStateInfo> deduplicatedStates = (Map<String, ToolStateInfo>) envData
			.get("_deduplicated_states");

		if (deduplicatedStates != null && !deduplicatedStates.isEmpty()) {
			// Use deduplicated states
			for (Map.Entry<String, ToolStateInfo> entry : deduplicatedStates.entrySet()) {
				ToolStateInfo stateInfo = entry.getValue();
				String key = entry.getKey();
				// Ignore ToolStateInfo with empty or null key
				if (key != null && !key.trim().isEmpty() && stateInfo != null && stateInfo.getStateString() != null
						&& !stateInfo.getStateString().trim().isEmpty()) {
					envDataStringBuilder.append(key).append(" context information:\n");
					envDataStringBuilder.append("    ").append(stateInfo.getStateString()).append("\n");
				}
			}
		}
		else {
			// Fallback to individual tool data (backward compatibility)
			for (String toolKey : availableToolKeys) {
				Object value = envData.get(toolKey);
				if (value == null) {
					continue;
				}
				if (value instanceof ToolStateInfo) {
					ToolStateInfo stateInfo = (ToolStateInfo) value;
					String key = stateInfo.getKey();
					// Ignore ToolStateInfo with empty or null key
					if (key != null && !key.trim().isEmpty() && stateInfo.getStateString() != null
							&& !stateInfo.getStateString().trim().isEmpty()) {
						envDataStringBuilder.append(toolKey).append(" context information:\n");
						envDataStringBuilder.append("    ").append(stateInfo.getStateString()).append("\n");
					}
				}
				else {
					String valueStr = value.toString();
					if (!valueStr.isEmpty()) {
						envDataStringBuilder.append(toolKey).append(" context information:\n");
						envDataStringBuilder.append("    ").append(valueStr).append("\n");
					}
				}
			}
		}

		return envDataStringBuilder.toString();
	}

	/**
	 * Asynchronously wait for user input or timeout. This method executes in a dedicated
	 * thread pool to avoid blocking business threads.
	 * @param formInputTool The form input tool to wait for
	 * @return CompletableFuture that completes when user input is received or timeout
	 * occurs
	 */
	private CompletableFuture<Void> waitForUserInputOrTimeoutAsync(FormInputTool formInputTool) {
		return CompletableFuture.runAsync(() -> {
			log.info("Waiting for user input for planId: {}...", getCurrentPlanId());
			long startTime = System.currentTimeMillis();
			long lastInterruptionCheck = startTime;
			// Get timeout from LynxeProperties and convert to milliseconds
			long userInputTimeoutMs = getLynxeProperties().getUserInputTimeout() * 1000L;
			long interruptionCheckIntervalMs = 2000L; // Check for interruption every 2
														// seconds

			while (formInputTool.getInputState() == FormInputTool.InputState.AWAITING_USER_INPUT) {
				long currentTime = System.currentTimeMillis();

				// Check for interruption periodically
				if (currentTime - lastInterruptionCheck >= interruptionCheckIntervalMs) {
					if (agentInterruptionHelper != null
							&& !agentInterruptionHelper.checkInterruptionAndContinue(getRootPlanId())) {
						log.info("User input wait interrupted for rootPlanId: {}", getRootPlanId());
						formInputTool.handleInputTimeout(); // Treat interruption as
															// timeout
						break;
					}
					lastInterruptionCheck = currentTime;
				}

				if (currentTime - startTime > userInputTimeoutMs) {
					log.warn("Timeout waiting for user input for planId: {}", getCurrentPlanId());
					formInputTool.handleInputTimeout(); // This will change its state to
					// INPUT_TIMEOUT
					break;
				}
				try {
					// Poll for input state change. In a real scenario, this might involve
					// a more sophisticated mechanism like a Future or a callback from the
					// UI.
					TimeUnit.MILLISECONDS.sleep(500); // Check every 500ms
				}
				catch (InterruptedException e) {
					log.warn("Interrupted while waiting for user input for planId: {}", getCurrentPlanId());
					Thread.currentThread().interrupt();
					formInputTool.handleInputTimeout(); // Treat interruption as timeout
														// for
					// simplicity
					break;
				}
			}
			if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_RECEIVED) {
				log.info("User input received for planId: {}", getCurrentPlanId());
			}
			else if (formInputTool.getInputState() == FormInputTool.InputState.INPUT_TIMEOUT) {
				log.warn("User input timed out for planId: {}", getCurrentPlanId());
			}
		}, FORM_INPUT_WAIT_EXECUTOR);
	}

}

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
package com.alibaba.cloud.ai.lynxe.exception.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

import com.alibaba.cloud.ai.lynxe.exception.PlanException;
import com.alibaba.cloud.ai.lynxe.planning.exception.PlanTemplateConfigException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * @author dahua
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/**
	 * Check if this is an SSE-related exception that should be ignored
	 */
	private boolean isSseException(WebRequest request) {
		if (request instanceof ServletWebRequest servletRequest) {
			HttpServletResponse response = servletRequest.getResponse();
			HttpServletRequest httpRequest = servletRequest.getRequest();

			// Check if response is already committed (SSE stream likely)
			if (response != null && response.isCommitted()) {
				return true;
			}

			// Check Content-Type header
			String contentType = response != null ? response.getContentType() : null;
			if (contentType != null && contentType.contains("text/event-stream")) {
				return true;
			}

			// Check Accept header
			String acceptHeader = httpRequest.getHeader("Accept");
			if (acceptHeader != null && acceptHeader.contains("text/event-stream")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Handle plan exceptions
	 */
	@SuppressWarnings("rawtypes")
	@ExceptionHandler(PlanException.class)
	public ResponseEntity handlePlanException(PlanException ex) {
		Map<String, Object> response = new HashMap<>();
		response.put("error", ex.getMessage());
		return ResponseEntity.internalServerError().body(response);
	}

	/**
	 * Handle PlanTemplateConfigException - return JSON format with errorCode
	 */
	@ExceptionHandler(PlanTemplateConfigException.class)
	public ResponseEntity<Map<String, Object>> handlePlanTemplateConfigException(PlanTemplateConfigException ex) {
		Map<String, Object> response = new HashMap<>();
		response.put("error", ex.getMessage());
		response.put("errorCode", ex.getErrorCode());
		return ResponseEntity.badRequest().body(response);
	}

	/**
	 * Handle SSE-related exceptions (broken pipe, disconnected clients) These should be
	 * silently ignored as they're expected when clients disconnect
	 */
	@ExceptionHandler({ AsyncRequestNotUsableException.class })
	public ResponseEntity<Void> handleSseException(AsyncRequestNotUsableException ex, WebRequest request) {
		// If this is an SSE-related exception, ignore it (response already committed or
		// client disconnected)
		if (isSseException(request)) {
			// Return empty response - Spring will handle committed responses gracefully
			// For committed responses, Spring won't try to write anything
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		}
		// Not an SSE request and response not committed, return error
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
	}

	/**
	 * Handle IOException that might be related to SSE (broken pipe)
	 */
	@ExceptionHandler(IOException.class)
	public ResponseEntity<Map<String, Object>> handleIOException(IOException ex, WebRequest request) {
		// If this is an SSE-related exception, ignore it (response already committed or
		// client disconnected)
		if (isSseException(request)) {
			// Return empty response - Spring will handle committed responses gracefully
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		}
		// Not an SSE request, handle normally
		Map<String, Object> errorResponse = new HashMap<>();
		errorResponse.put("error", ex.getMessage());
		return ResponseEntity.internalServerError().body(errorResponse);
	}

	/**
	 * Handle all uncaught exceptions
	 */
	@SuppressWarnings("rawtypes")
	@ExceptionHandler(Exception.class)
	public ResponseEntity handleGlobalException(Exception ex, WebRequest request) {
		// Skip SSE-related exceptions
		if (ex instanceof AsyncRequestNotUsableException) {
			// Already handled by handleSseException
			return handleSseException((AsyncRequestNotUsableException) ex, request);
		}

		// Check if this is an SSE-related exception (broken pipe, client disconnected)
		if (isSseException(request)) {
			// SSE request with broken pipe - silently ignore
			// Return empty response - Spring will handle committed responses gracefully
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		}

		Map<String, Object> response = new HashMap<>();
		response.put("error", ex.getMessage());
		return ResponseEntity.internalServerError().body(response);
	}

}

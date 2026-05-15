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

import { computed } from 'vue'
import { useTaskStore } from '@/stores/task'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'

/**
 * Unified composable for task execution state management
 * Single Source of Truth for all components checking execution state
 *
 * This composable provides a consistent interface for checking task execution state
 * across all components (InputArea, ExecutionController, etc.)
 *
 * Unified state: messageDialog.isRunning is the single source of truth
 */
export function useTaskExecutionState() {
  const taskStore = useTaskStore()
  const messageDialog = useMessageDialogSingleton()

  /**
   * Check if a task is currently running
   * Includes both plan execution (with planId) and chat streaming (without planId)
   * Uses messageDialog.isRunning as the single source of truth
   */
  const isTaskRunning = computed(() => {
    // Check if there's a running plan execution (with planId)
    const hasRunningPlan = taskStore.hasRunningTask()
    // Check if there's active chat streaming (without planId but isRunning is true)
    const hasActiveStream = messageDialog.isRunning.value && !taskStore.currentTask?.planId
    return hasRunningPlan || hasActiveStream
  })

  /**
   * Check if execution is in progress
   * Uses unified isRunning state
   */
  const isExecutionInProgress = computed(() => {
    return messageDialog.isRunning.value
  })

  /**
   * Check if execution can be started
   * Validates all conditions before allowing execution
   */
  const canExecute = computed(() => {
    // Block if execution is already in progress
    return !messageDialog.isRunning.value
  })

  /**
   * Get current running plan ID
   */
  const getCurrentPlanId = computed(() => {
    return taskStore.currentTask?.planId || null
  })

  return {
    // State
    isTaskRunning,
    isExecutionInProgress,
    canExecute,
    currentPlanId: getCurrentPlanId,
  }
}

// Singleton instance for global use
let singletonInstance: ReturnType<typeof useTaskExecutionState> | null = null

/**
 * Get or create singleton instance of useTaskExecutionState
 * This ensures all components use the same state instance
 */
export function useTaskExecutionStateSingleton() {
  if (!singletonInstance) {
    singletonInstance = useTaskExecutionState()
  }
  return singletonInstance
}

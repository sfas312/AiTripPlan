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

import { DirectApiService } from '@/api/direct-api-service'
import { useMessageDialogSingleton } from '@/composables/useMessageDialog'
import { usePlanExecutionSingleton } from '@/composables/usePlanExecution'
import { useTaskStore } from '@/stores/task'
import { computed, ref } from 'vue'

/**
 * Composable for handling task stop functionality
 * Provides reusable stop logic for components that need to stop running tasks
 */
export function useTaskStop() {
  const taskStore = useTaskStore()
  const planExecution = usePlanExecutionSingleton()
  const messageDialog = useMessageDialogSingleton()
  const isStopping = ref(false)

  /**
   * Check if there's a running task that can be stopped
   * Supports both plan execution (with planId) and chat streaming (with activeStreamAbortController)
   */
  const canStop = computed(() => {
    const hasPlanId = taskStore.hasRunningTask() && !!taskStore.currentTask?.planId
    const hasActiveStream =
      messageDialog.isRunning.value && !!messageDialog.activeStreamAbortController.value
    return hasPlanId || hasActiveStream
  })

  /**
   * Stop a running task by plan ID or stop active chat streaming
   * Checks execution status before and after stopping to handle backend restart scenarios
   * @param planId Plan ID to stop. If not provided, uses planId from currentTask or stops streaming
   * @returns Promise<boolean> - true if stop was successful or task was already stopped, false otherwise
   */
  const stopTask = async (planId?: string): Promise<boolean> => {
    if (isStopping.value) {
      console.log('[useTaskStop] Stop already in progress, skipping')
      return false
    }

    // Check if there's an active chat stream to stop
    const hasActiveStream = !!messageDialog.activeStreamAbortController.value
    const hasStreamId = !!messageDialog.currentStreamId.value
    const targetPlanId = planId || taskStore.currentTask?.planId

    // If no planId but there's an active stream with streamId, stop the stream
    if (!targetPlanId && hasActiveStream && hasStreamId) {
      console.log('[useTaskStop] Stopping active chat stream')
      isStopping.value = true
      try {
        await messageDialog.stopChatStreaming()
        console.log('[useTaskStop] Chat stream stopped successfully')
        return true
      } catch (error) {
        // stopChatStreaming now handles errors gracefully (stream may have already completed)
        // Log but don't throw - cleanup was already done in stopChatStreaming
        const errorMessage = error instanceof Error ? error.message : String(error)
        if (errorMessage.includes('missing required parameters')) {
          // Only throw if we're missing required parameters (shouldn't happen)
          console.error('[useTaskStop] Failed to stop chat stream:', error)
          throw error
        } else {
          // Stream may have already completed, treat as success
          console.log(
            '[useTaskStop] Chat stream cleanup completed (stream may have already finished)'
          )
          return true
        }
      } finally {
        isStopping.value = false
      }
    }

    // If no planId and no active stream with streamId, nothing to stop
    if (!targetPlanId) {
      if (hasActiveStream && !hasStreamId) {
        const errorMsg = 'Cannot stop chat stream: streamId is required but not available'
        console.error('[useTaskStop]', errorMsg)
        throw new Error(errorMsg)
      }
      console.warn('[useTaskStop] No planId or active stream available to stop')
      return false
    }

    console.log('[useTaskStop] Stopping task for planId:', targetPlanId)
    isStopping.value = true

    try {
      // If there's also an active stream with streamId, stop it first
      if (hasActiveStream && hasStreamId) {
        console.log('[useTaskStop] Also stopping active chat stream')
        try {
          await messageDialog.stopChatStreaming()
        } catch (error) {
          // stopChatStreaming handles errors gracefully - stream may have already completed
          // Log but continue with plan stop
          const errorMessage = error instanceof Error ? error.message : String(error)
          if (!errorMessage.includes('missing required parameters')) {
            console.log(
              '[useTaskStop] Chat stream cleanup completed (may have already finished), continuing with plan stop'
            )
          } else {
            console.warn(
              '[useTaskStop] Failed to stop chat stream, continuing with plan stop:',
              error
            )
          }
        }
      } else if (hasActiveStream && !hasStreamId) {
        // Missing streamId but has active stream - log warning but continue with plan stop
        console.warn(
          '[useTaskStop] Cannot stop chat stream: streamId is required but not available, continuing with plan stop'
        )
      }
      // Optimistic update: immediately update state for instant UI feedback
      // Reset isRunning and clear planId
      messageDialog.setIsRunning(false)
      if (taskStore.currentTask) {
        taskStore.currentTask.planId = undefined
      }

      // Untrack plan immediately
      if (planExecution.trackedPlanIds.value.has(targetPlanId)) {
        planExecution.untrackPlan(targetPlanId)
        console.log('[useTaskStop] Untracked plan:', targetPlanId)
      }

      // Update plan execution record to mark as stopped
      const record = planExecution.getPlanExecutionRecord(targetPlanId)
      if (record && (!record.completed || record.status !== 'failed')) {
        planExecution.setCachedPlanRecord(targetPlanId, {
          ...record,
          completed: true,
          status: 'failed',
          summary: record.summary || 'Task stopped by user',
        })
        console.log('[useTaskStop] Marked plan execution record as stopped:', targetPlanId)
      }

      // Check execution status before stopping to handle backend restart scenario
      let taskStatus
      try {
        taskStatus = await DirectApiService.getTaskStatus(targetPlanId)
        console.log('[useTaskStop] Task status before stop:', taskStatus)

        // If task doesn't exist or is not running, state already updated optimistically
        if (!taskStatus.exists || !taskStatus.isRunning) {
          console.log(
            '[useTaskStop] Task is not actually running (backend may have restarted), state already updated'
          )
          isStopping.value = false
          return true // Consider this a success since task is already stopped
        }
      } catch (statusError) {
        console.warn(
          '[useTaskStop] Failed to check task status, proceeding with stop:',
          statusError
        )
        // Continue with stop attempt even if status check fails
      }

      // Stop the task
      await DirectApiService.stopTask(targetPlanId)
      console.log('[useTaskStop] Task stop request sent successfully')

      // Verify status after stopping (optional, for confirmation)
      try {
        // Wait a bit for the backend to process the stop request
        await new Promise(resolve => setTimeout(resolve, 500))
        taskStatus = await DirectApiService.getTaskStatus(targetPlanId)
        console.log('[useTaskStop] Task status after stop:', taskStatus)

        // Update state based on actual backend status (if different from optimistic update)
        if (!taskStatus.isRunning && taskStore.currentTask?.planId === targetPlanId) {
          messageDialog.setIsRunning(false)
          taskStore.currentTask.planId = undefined
          console.log('[useTaskStop] Task confirmed stopped, updated frontend state')
        }
      } catch (statusError) {
        console.warn('[useTaskStop] Failed to verify task status after stop:', statusError)
        // State already updated optimistically, so this is fine
      }

      return true
    } catch (error) {
      console.error('[useTaskStop] Failed to stop task:', error)
      // Keep state updated (user clicked stop, so state should reflect that)
      messageDialog.setIsRunning(false)
      if (taskStore.currentTask) {
        taskStore.currentTask.planId = undefined
      }
      return false
    } finally {
      isStopping.value = false
    }
  }

  return {
    stopTask,
    isStopping,
    canStop,
  }
}

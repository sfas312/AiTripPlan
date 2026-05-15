<!--
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
-->
<template>
  <div class="recursive-sub-plan" :class="getNestingClass()">
    <!-- Sub-plan header -->
    <div
      class="sub-plan-header"
      @click.stop="handleSubPlanClick"
      :title="$t('chat.clickToViewExecutionDetails')"
    >
      <div class="sub-plan-info">
        <div class="sub-plan-details">
          <div class="sub-plan-title">
            {{ subPlan.title || $t('chat.subPlan') }} #{{ subPlanIndex + 1 }}
          </div>
          <div class="request-content">
            <span class="click-hint">{{ $t('chat.clickToViewExecutionDetails') }}</span>
          </div>
        </div>
      </div>
      <div class="sub-plan-controls">
        <div class="sub-plan-status-badge" :class="getSubPlanStatusClass()">
          {{ getSubPlanStatusText() }}
        </div>
      </div>
    </div>

    <!-- Agent tool info -->
    <div
      v-if="
        firstAgent &&
        (firstAgent.agentRequest ||
          firstAgent.latestMethodName ||
          firstAgent.latestMethodArgs ||
          firstAgent.latestRoundNumber)
      "
      class="agent-tool-info"
    >
      <div
        class="tool-info-header"
        @click="toggleToolInfo"
        :class="{ expanded: isToolInfoExpanded }"
      >
        <span
          v-if="
            firstAgent.agentName === 'ConfigurableDynaAgent' &&
            firstAgent.latestRoundNumber !== undefined &&
            firstAgent.latestRoundNumber !== null
          "
          class="tool-info-round-info"
        >
          {{ $t('chat.roundLabel', { round: firstAgent.latestRoundNumber }) }}
        </span>
        <span v-if="firstAgent.latestMethodName" class="tool-info-method-name">
          {{ firstAgent.latestMethodName }}
        </span>
        <Icon
          :icon="isToolInfoExpanded ? 'carbon:chevron-up' : 'carbon:chevron-right'"
          class="tool-info-toggle-icon"
        />
      </div>
      <div v-if="isToolInfoExpanded" class="tool-info-content">
        <!-- User request detail -->
        <div v-if="firstAgent.agentRequest" class="tool-info-item">
          <Icon icon="carbon:chat" class="tool-info-item-icon" />
          <span class="tool-info-item-label">{{ $t('chat.userRequest') }}:</span>
          <pre class="tool-info-item-value tool-args-content">{{ firstAgent.agentRequest }}</pre>
        </div>
        <div v-if="firstAgent.latestMethodName" class="tool-info-item">
          <Icon icon="carbon:code" class="tool-info-item-icon" />
          <span class="tool-info-item-label">{{ $t('chat.methodName') }}:</span>
          <span class="tool-info-item-value">{{ firstAgent.latestMethodName }}</span>
        </div>
        <div v-if="firstAgent.latestMethodArgs" class="tool-info-item">
          <Icon icon="carbon:settings" class="tool-info-item-icon" />
          <span class="tool-info-item-label">{{ $t('chat.methodArgs') }}:</span>
          <pre class="tool-info-item-value tool-args-content">{{
            formatToolParameters(firstAgent.latestMethodArgs)
          }}</pre>
        </div>
      </div>
    </div>

    <!-- Direct sub-plans from all agents -->
    <div v-if="allDirectSubPlans.length > 0" class="direct-sub-plans">
      <div class="direct-sub-plans-header">
        <Icon icon="carbon:tree-view" class="direct-icon" />
        <span class="direct-label">
          {{ $t('chat.subPlanExecutions') }} ({{ allDirectSubPlans.length }})
        </span>
      </div>
      <div class="direct-sub-plans-list">
        <RecursiveSubPlan
          v-for="(directSubPlan, directIndex) in allDirectSubPlans"
          :key="directSubPlan.currentPlanId || directIndex"
          :sub-plan="directSubPlan"
          :sub-plan-index="directIndex"
          :nesting-level="(nestingLevel ?? 0) + 1"
          :max-nesting-depth="maxNestingDepth ?? 3"
          :max-visible-steps="maxVisibleSteps ?? 2"
          @sub-plan-selected="handleNestedSubPlanSelected"
          @step-selected="handleNestedStepSelected"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { AgentExecutionRecord, PlanExecutionRecord } from '@/types/plan-execution-record'
import { Icon } from '@iconify/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  subPlan: PlanExecutionRecord
  subPlanIndex: number
  nestingLevel?: number
  maxNestingDepth?: number
  maxVisibleSteps?: number
}

interface Emits {
  (
    e: 'sub-plan-selected',
    agentIndex: number,
    subPlanIndex: number,
    subPlan: PlanExecutionRecord
  ): void
  (e: 'step-selected', stepId: string): void
}

const props = withDefaults(defineProps<Props>(), {
  nestingLevel: 0,
  maxNestingDepth: 3,
  maxVisibleSteps: 2,
})

const emit = defineEmits<Emits>()

// Initialize i18n
const { t } = useI18n()

// Helper functions
const getNestingClass = (): string => {
  return `nesting-level-${props.nestingLevel}`
}

// Sub-plan status methods
const getSubPlanStatusClass = (): string => {
  if (props.subPlan.completed) {
    return 'completed'
  }

  const hasRunningAgent = props.subPlan.agentExecutionSequence?.some(
    agent => agent.status === 'RUNNING'
  )
  if (hasRunningAgent) {
    return 'running'
  }

  const hasFinishedAgent = props.subPlan.agentExecutionSequence?.some(
    agent => agent.status === 'FINISHED'
  )
  if (hasFinishedAgent) {
    return 'in-progress'
  }

  return 'pending'
}

const getSubPlanStatusText = (): string => {
  const statusClass = getSubPlanStatusClass()
  switch (statusClass) {
    case 'completed':
      return t('chat.status.completed')
    case 'running':
      return t('chat.status.executing')
    case 'in-progress':
      return t('chat.status.inProgress')
    case 'pending':
      return t('chat.status.pending')
    default:
      return t('chat.status.unknown')
  }
}

// Unused function - kept for potential future use
// const getSubPlanProgress = (): number => {
//   if (!props.subPlan.agentExecutionSequence?.length) return 0
//   if (props.subPlan.completed) return 100
//
//   const completedCount = getSubPlanCompletedCount()
//   return Math.min(100, (completedCount / props.subPlan.agentExecutionSequence.length) * 100)
// }

// Unused function - kept for potential future use
// const getSubPlanCompletedCount = (): number => {
//   if (!props.subPlan.agentExecutionSequence?.length) return 0
//   return props.subPlan.agentExecutionSequence.filter(agent => agent.status === 'FINISHED').length
// }

// Get first agent for tool info display
const firstAgent = computed((): AgentExecutionRecord | undefined => {
  return props.subPlan.agentExecutionSequence?.[0]
})

// Collect all direct sub-plans from all agents
const allDirectSubPlans = computed(() => {
  const subPlans: PlanExecutionRecord[] = []
  if (props.subPlan.agentExecutionSequence) {
    for (const agent of props.subPlan.agentExecutionSequence) {
      if (agent.subPlanExecutionRecords) {
        subPlans.push(...agent.subPlanExecutionRecords)
      }
    }
  }
  return subPlans
})

// Collapsible state for tool info
const toolInfoExpanded = ref(false)

// Toggle tool info expansion
const toggleToolInfo = () => {
  toolInfoExpanded.value = !toolInfoExpanded.value
}

// Check if tool info is expanded
const isToolInfoExpanded = computed(() => toolInfoExpanded.value)

// Format tool parameters
const formatToolParameters = (parameters?: string): string => {
  if (!parameters) return ''

  try {
    const parsed = JSON.parse(parameters)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return parameters
  }
}

// Event handlers
const handleSubPlanClick = (event?: Event) => {
  console.log('[RecursiveSubPlan] handleSubPlanClick called', {
    subPlanIndex: props.subPlanIndex,
    subPlanId: props.subPlan.currentPlanId,
    eventTarget: event?.target,
  })
  emit('sub-plan-selected', -1, props.subPlanIndex, props.subPlan)
}

const handleNestedSubPlanSelected = (
  agentIndex: number,
  subPlanIndex: number,
  subPlan: PlanExecutionRecord
) => {
  emit('sub-plan-selected', agentIndex, subPlanIndex, subPlan)
}

const handleNestedStepSelected = (stepId: string) => {
  emit('step-selected', stepId)
}
</script>

<style lang="less" scoped>
.recursive-sub-plan {
  background: rgba(102, 126, 234, 0.05);
  border: 1px solid rgba(102, 126, 234, 0.1);
  border-radius: 6px;
  padding: 0;
  margin-bottom: 8px;
  transition: all 0.2s ease;
  overflow: hidden;

  &:hover {
    background: rgba(102, 126, 234, 0.1);
    border-color: rgba(102, 126, 234, 0.2);
  }

  &.running {
    border-color: rgba(102, 126, 234, 0.3);
    background: rgba(102, 126, 234, 0.08);
    box-shadow: 0 0 8px rgba(102, 126, 234, 0.15);
  }

  &.completed {
    border-color: rgba(34, 197, 94, 0.3);
    background: rgba(34, 197, 94, 0.05);
  }

  &.pending {
    opacity: 0.7;
  }

  // Nesting level styles
  &.nesting-level-1 {
    margin-left: 16px;
    border-left: 3px solid rgba(102, 126, 234, 0.3);
  }

  &.nesting-level-2 {
    margin-left: 32px;
    border-left: 3px solid rgba(251, 191, 36, 0.3);
  }

  &.nesting-level-3 {
    margin-left: 48px;
    border-left: 3px solid rgba(34, 197, 94, 0.3);
  }

  .sub-plan-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
    background: rgba(255, 255, 255, 0.02);
    cursor: pointer;
    transition: background 0.2s ease;
    margin-bottom: 8px;
    user-select: none;
    position: relative;
    z-index: 1;

    &:hover {
      background: rgba(255, 255, 255, 0.05);
    }

    &:active {
      background: rgba(255, 255, 255, 0.08);
    }

    .sub-plan-info {
      display: flex;
      align-items: center;
      gap: 12px;
      flex: 1;
      pointer-events: none;

      .sub-plan-details {
        .sub-plan-title {
          font-weight: 600;
          color: #ffffff;
          font-size: 14px;
          margin-bottom: 2px;
          display: flex;
          align-items: center;
          gap: 6px;

          .nesting-level {
            color: #888888;
            font-size: 10px;
            font-weight: 400;
            background: rgba(136, 136, 136, 0.2);
            padding: 1px 4px;
            border-radius: 3px;
          }
        }

        .request-content {
          margin: 4px 0 0 0;
          padding: 4px 0px;
          color: #aaaaaa;
          font-size: 12px;
          font-style: italic;

          .round-info {
            color: #667eea;
            font-weight: 500;
            margin-right: 8px;
            font-style: normal;
          }

          .click-hint {
            font-size: 10px;
          }
        }
      }
    }

    .sub-plan-controls {
      display: flex;
      align-items: center;
      gap: 12px;
      pointer-events: none;

      .sub-plan-status-badge {
        padding: 3px 8px;
        border-radius: 10px;
        font-size: 11px;
        font-weight: 500;
        flex-shrink: 0;

        &.completed {
          background: rgba(34, 197, 94, 0.2);
          color: #22c55e;
        }

        &.running {
          background: rgba(102, 126, 234, 0.2);
          color: #667eea;
        }

        &.in-progress {
          background: rgba(251, 191, 36, 0.2);
          color: #fbbf24;
        }

        &.pending {
          background: rgba(156, 163, 175, 0.2);
          color: #9ca3af;
        }
      }
    }
  }

  .agent-tool-info {
    margin-top: 8px;
    margin-bottom: 8px;

    .tool-info-header {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 4px;
      transition: background 0.2s ease;

      &:hover {
        background: rgba(255, 255, 255, 0.05);
      }

      &.expanded {
        margin-bottom: 8px;
      }

      .tool-info-round-info {
        color: #667eea;
        font-weight: 500;
        font-size: 13px;
        white-space: nowrap;
        line-height: 1.5;
      }

      .tool-info-method-name {
        flex: 1;
        color: #ffffff;
        font-size: 13px;
        font-weight: 500;
        word-break: break-word;
        line-height: 1.5;
      }

      .tool-info-toggle-icon {
        font-size: 14px;
        color: #aaaaaa;
        transition: transform 0.2s ease;
        flex-shrink: 0;
      }

      &.expanded .tool-info-toggle-icon {
        transform: rotate(90deg);
      }
    }

    .tool-info-content {
      padding: 8px 12px;
      background: rgba(0, 0, 0, 0.1);
      border-radius: 4px;
      margin-top: 4px;

      .tool-info-item {
        display: flex;
        align-items: flex-start;
        gap: 8px;
        margin-bottom: 8px;

        &:last-child {
          margin-bottom: 0;
        }

        .tool-info-item-icon {
          font-size: 14px;
          color: #667eea;
          margin-top: 2px;
          flex-shrink: 0;
        }

        .tool-info-item-label {
          color: #aaaaaa;
          font-size: 12px;
          font-weight: 500;
          min-width: 80px;
          flex-shrink: 0;
        }

        .tool-info-item-value {
          flex: 1;
          color: #ffffff;
          font-size: 12px;
          margin: 0;

          &.tool-args-content {
            font-family: monospace;
            background: rgba(0, 0, 0, 0.2);
            padding: 6px;
            border-radius: 3px;
            white-space: pre-wrap;
            word-break: break-word;
          }
        }
      }
    }
  }

  .direct-sub-plans {
    margin-top: 8px;
    margin-bottom: 8px;

    .direct-sub-plans-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;

      .direct-icon {
        font-size: 11px;
        color: #667eea;
      }

      .direct-label {
        color: #ffffff;
        font-weight: 600;
        font-size: 11px;
      }
    }

    .direct-sub-plans-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
  }

  .sub-plan-agents-steps {
    padding: 12px;

    .agents-steps-header {
      color: #aaaaaa;
      font-size: 11px;
      margin-bottom: 6px;
      font-weight: 500;
    }

    .agents-steps-list {
      display: flex;
      flex-direction: column;
      gap: 8px;

      .agent-step-item {
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 6px;
        padding: 8px;
        background: rgba(0, 0, 0, 0.05);
        cursor: pointer;
        transition: all 0.2s;

        &:hover {
          background: rgba(0, 0, 0, 0.1);
          border-color: rgba(255, 255, 255, 0.2);
        }

        &.completed {
          border-color: rgba(34, 197, 94, 0.3);
          background: rgba(34, 197, 94, 0.05);
        }

        &.running {
          border-color: rgba(102, 126, 234, 0.3);
          background: rgba(102, 126, 234, 0.08);
        }

        &.pending {
          opacity: 0.7;
        }

        .agent-step-header {
          display: flex;
          align-items: center;
          gap: 8px;
          margin-bottom: 8px;

          .agent-icon {
            font-size: 14px;

            &.completed {
              color: #22c55e;
            }

            &.running {
              color: #667eea;
            }

            &.pending {
              color: #9ca3af;
            }
          }

          .agent-name {
            color: #ffffff;
            font-size: 13px;
            font-weight: 500;
            flex: 1;
          }

          .agent-status-badge {
            padding: 2px 6px;
            border-radius: 3px;
            font-size: 10px;
            font-weight: 500;

            &.completed {
              background: rgba(34, 197, 94, 0.2);
              color: #22c55e;
            }

            &.running {
              background: rgba(102, 126, 234, 0.2);
              color: #667eea;
            }

            &.pending {
              background: rgba(156, 163, 175, 0.2);
              color: #9ca3af;
            }
          }
        }

        .sub-agent-execution-info {
          margin-left: 22px;

          .agent-result,
          .agent-error {
            margin-bottom: 8px;

            &:last-child {
              margin-bottom: 0;
            }

            .result-header,
            .error-header {
              display: flex;
              align-items: center;
              gap: 4px;
              margin-bottom: 4px;

              .result-icon,
              .error-icon {
                font-size: 12px;
              }

              .result-icon {
                color: #22c55e;
              }

              .error-icon {
                color: #ef4444;
              }

              .result-label,
              .error-label {
                color: #ffffff;
                font-size: 11px;
                font-weight: 500;
              }
            }

            .result-content,
            .error-content {
              margin: 0;
              padding: 6px;
              background: rgba(0, 0, 0, 0.2);
              border-radius: 3px;
              font-family: monospace;
              font-size: 10px;
              white-space: pre-wrap;
              max-height: 80px;
              overflow-y: auto;
              color: #cccccc;
              line-height: 1.3;
            }
          }

          .think-act-preview {
            margin-top: 8px;

            .think-act-header {
              display: flex;
              align-items: center;
              gap: 4px;
              margin-bottom: 6px;

              .think-act-icon {
                font-size: 12px;
                color: #667eea;
              }

              .think-act-label {
                color: #aaaaaa;
                font-size: 11px;
                font-weight: 500;
              }
            }

            .think-act-steps-preview {
              display: flex;
              flex-direction: column;
              gap: 3px;

              .think-act-step-preview {
                display: flex;
                align-items: center;
                gap: 6px;
                padding: 4px 6px;
                background: rgba(0, 0, 0, 0.1);
                border-radius: 3px;
                cursor: pointer;
                transition: all 0.2s;
                font-size: 10px;

                &:hover {
                  background: rgba(0, 0, 0, 0.2);
                }

                .step-number {
                  color: #667eea;
                  font-weight: 500;
                  min-width: 20px;
                }

                .step-description {
                  color: #cccccc;
                  flex: 1;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }

                .step-arrow {
                  font-size: 10px;
                  color: #888888;
                }
              }

              .more-steps {
                padding: 2px 6px;
                color: #888888;
                font-size: 9px;
                font-style: italic;
              }
            }
          }

          .nested-sub-plans {
            margin-top: 12px;

            .nested-sub-plans-header {
              display: flex;
              align-items: center;
              gap: 8px;
              margin-bottom: 12px;

              .nested-icon {
                font-size: 11px;
                color: #667eea;
              }

              .nested-label {
                color: #aaaaaa;
                font-size: 11px;
                font-weight: 500;
              }
            }

            .nested-sub-plans-list {
              display: flex;
              flex-direction: column;
              gap: 6px;
            }
          }
        }
      }
    }
  }
}
</style>

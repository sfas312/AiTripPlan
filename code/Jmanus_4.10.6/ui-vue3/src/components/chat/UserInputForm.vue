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
  <div class="user-input-form-container">
    <div class="user-input-header">
      <Icon icon="carbon:user" class="user-icon" />
      <h4 class="user-input-title">{{ $t('chat.userInputRequired') }}</h4>
    </div>

    <p class="user-input-message">
      {{ userInputWaitState?.title ?? $t('chat.userInput.message') }}
    </p>

    <div
      v-if="userInputWaitState?.formDescription"
      class="form-description"
      v-html="formattedDescription"
    ></div>

    <form @submit.prevent="handleUserInputSubmit" class="user-input-form">
      <template v-if="userInputWaitState?.formInputs && userInputWaitState.formInputs.length > 0">
        <div class="form-grid">
          <div
            v-for="(input, inputIndex) in userInputWaitState?.formInputs"
            :key="inputIndex"
            class="form-group"
          >
            <label :for="`form-input-${input.label.replace(/\W+/g, '_')}`">
              {{ input.label }}{{ isRequired(input.required) ? ' *' : '' }}:
            </label>

            <!-- Form input types -->
            <input
              v-if="!input.type || input.type === 'text'"
              type="text"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input"
            />

            <input
              v-else-if="input.type === 'email'"
              type="email"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input"
            />

            <input
              v-else-if="input.type === 'number'"
              type="number"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input"
            />

            <input
              v-else-if="input.type === 'password'"
              type="password"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input"
            />

            <textarea
              v-else-if="input.type === 'textarea'"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input form-textarea"
              rows="4"
            ></textarea>

            <select
              v-else-if="input.type === 'select' && input.options"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input form-select"
            >
              <option value="">{{ $t('selectCommon.pleaseSelect') }}</option>
              <option
                v-for="option in getOptionsArray(input.options)"
                :key="option"
                :value="option"
              >
                {{ option }}
              </option>
            </select>

            <div v-else-if="input.type === 'checkbox' && input.options" class="checkbox-group">
              <label
                v-for="option in getOptionsArray(input.options)"
                :key="option"
                class="checkbox-item"
              >
                <input
                  type="checkbox"
                  :id="`form-input-${input.label.replace(/\W+/g, '_')}-${option.replace(/\W+/g, '_')}`"
                  :name="input.label"
                  :value="option"
                  v-model="formInputsStore[inputIndex]"
                  class="form-checkbox"
                />
                <span class="checkbox-label">{{ option }}</span>
              </label>
            </div>

            <div v-else-if="input.type === 'radio' && input.options" class="radio-group">
              <label
                v-for="option in getOptionsArray(input.options)"
                :key="option"
                class="radio-item"
              >
                <input
                  type="radio"
                  :id="`form-input-${input.label.replace(/\W+/g, '_')}-${option.replace(/\W+/g, '_')}`"
                  :name="input.label"
                  :value="option"
                  v-model="formInputsStore[inputIndex]"
                  class="form-radio"
                />
                <span class="radio-label">{{ option }}</span>
              </label>
            </div>

            <input
              v-else
              type="text"
              :id="`form-input-${input.label.replace(/\W+/g, '_')}`"
              :name="input.label"
              :placeholder="input.placeholder || ''"
              :required="isRequired(input.required)"
              v-model="formInputsStore[inputIndex]"
              class="form-input"
            />
          </div>
        </div>
      </template>

      <template v-else>
        <div class="form-group">
          <label for="form-input-genericInput">{{ $t('common.input') }}:</label>
          <input
            type="text"
            id="form-input-genericInput"
            name="genericInput"
            v-model="genericInput"
            class="form-input"
          />
        </div>
      </template>

      <button type="submit" class="submit-user-input-btn">
        {{ $t('chat.userInput.submit') }}
      </button>
    </form>
  </div>
</template>

<script setup lang="ts">
import { CommonApiService } from '@/api/common-api-service'
import { useMessageFormatting } from '@/components/chat/composables/useMessageFormatting'
import type { UserInputWaitState } from '@/types/plan-execution-record'
import { Icon } from '@iconify/vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  userInputWaitState?: UserInputWaitState
  planId?: string
  genericInput?: string
}

interface Emits {
  (e: 'user-input-submitted', inputData: Record<string, unknown>): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

// Initialize i18n
useI18n()

// Initialize message formatting for Markdown rendering
const { formatResponseText } = useMessageFormatting()

// Format form description as Markdown
const formattedDescription = computed(() => {
  if (!props.userInputWaitState?.formDescription) {
    return ''
  }
  return formatResponseText(props.userInputWaitState.formDescription)
})

// Local state
const formInputsStore = reactive<Record<number, string | string[]>>({})
const genericInput = ref(props.genericInput ?? '')
const isInitialized = ref(false)
const initializationTimeout = ref<ReturnType<typeof setTimeout> | null>(null)

// Initialize form inputs based on type
const initializeFormInputs = () => {
  const formInputs = props.userInputWaitState?.formInputs
  if (formInputs && formInputs.length > 0) {
    // Only initialize if not already initialized or if form structure changed
    const needsInitialization =
      !isInitialized.value || Object.keys(formInputsStore).length !== formInputs.length

    if (needsInitialization) {
      formInputs.forEach((input, index) => {
        // Only initialize if this field doesn't exist or is empty
        if (
          !(index in formInputsStore) ||
          (input.type === 'checkbox' && !Array.isArray(formInputsStore[index])) ||
          (input.type !== 'checkbox' && formInputsStore[index] === '')
        ) {
          if (input.type === 'checkbox') {
            formInputsStore[index] = []
          } else {
            formInputsStore[index] = ''
          }
        }
      })
      isInitialized.value = true
    }
  }
}

// Initialize on mount and when formInputs change
onMounted(() => {
  initializeFormInputs()
})

watch(
  () => props.userInputWaitState?.formInputs,
  () => {
    // Clear any existing timeout
    if (initializationTimeout.value) {
      clearTimeout(initializationTimeout.value)
    }

    // Debounce initialization to avoid frequent resets
    initializationTimeout.value = setTimeout(() => {
      // Reset initialization flag when form structure changes
      isInitialized.value = false
      initializeFormInputs()
    }, 100) // 100ms debounce
  },
  { deep: true }
)

// Event handlers
const handleUserInputSubmit = async () => {
  try {
    const inputData: Record<string, unknown> = {}

    const formInputs = props.userInputWaitState?.formInputs
    if (formInputs && formInputs.length > 0) {
      Object.entries(formInputsStore).forEach(([index, value]) => {
        const numIndex = parseInt(index, 10)
        const input = formInputs[numIndex]
        const label = input.label || `input_${index}`
        // Handle checkbox arrays - convert to string if it's an array
        if (Array.isArray(value)) {
          inputData[label] = value.join(', ')
        } else {
          inputData[label] = value
        }
      })
    } else {
      inputData.genericInput = genericInput.value
    }

    console.log('[UserInputForm] Submitting user input:', inputData, 'for planId:', props.planId)

    // Submit user input to backend API
    if (props.planId) {
      await CommonApiService.submitFormInput(props.planId, inputData)
      console.log('[UserInputForm] User input submitted successfully')
    } else {
      console.error('[UserInputForm] No planId available for user input submission')
    }

    emit('user-input-submitted', inputData)
  } catch (error: unknown) {
    console.error('[UserInputForm] User input submission failed:', error)
  }
}

// Helper methods
const getOptionsArray = (options: string | string[] | undefined): string[] => {
  if (!options) return []
  if (Array.isArray(options)) return options
  if (typeof options === 'string') {
    return options
      .split(',')
      .map(opt => opt.trim())
      .filter(opt => opt.length > 0)
  }
  return []
}

const isRequired = (required: boolean | string | undefined): boolean => {
  if (typeof required === 'boolean') return required
  if (typeof required === 'string') return required === 'true'
  return false
}

// Cleanup on unmount
onUnmounted(() => {
  if (initializationTimeout.value) {
    clearTimeout(initializationTimeout.value)
  }
})
</script>

<style lang="less" scoped>
.user-input-form-container {
  margin-top: 20px;
  padding: 16px;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 8px;

  .user-input-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;

    .user-icon {
      font-size: 16px;
      color: #667eea;
    }

    .user-input-title {
      margin: 0;
      color: #ffffff;
      font-size: 14px;
      font-weight: 600;
    }
  }

  .user-input-message {
    margin-bottom: 12px;
    font-weight: 500;
    color: #ffffff;
    font-size: 14px;
  }

  .form-description {
    margin-bottom: 16px;
    color: #aaaaaa;
    font-size: 13px;
    line-height: 1.6;

    // Markdown content styling
    :deep(h1),
    :deep(h2),
    :deep(h3),
    :deep(h4),
    :deep(h5),
    :deep(h6) {
      color: #ffffff;
      margin-top: 12px;
      margin-bottom: 8px;
      font-weight: 600;
    }

    :deep(h1) {
      font-size: 18px;
    }

    :deep(h2) {
      font-size: 16px;
    }

    :deep(h3) {
      font-size: 15px;
    }

    :deep(p) {
      margin-bottom: 8px;
    }

    :deep(code) {
      background: rgba(0, 0, 0, 0.3);
      padding: 2px 6px;
      border-radius: 3px;
      font-family: 'Courier New', monospace;
      font-size: 12px;
      color: #f0f0f0;
    }

    :deep(pre) {
      background: rgba(0, 0, 0, 0.3);
      padding: 12px;
      border-radius: 6px;
      overflow-x: auto;
      margin-bottom: 12px;
    }

    :deep(pre code) {
      background: transparent;
      padding: 0;
    }

    :deep(ul),
    :deep(ol) {
      margin-left: 20px;
      margin-bottom: 8px;
    }

    :deep(li) {
      margin-bottom: 4px;
    }

    :deep(blockquote) {
      border-left: 3px solid #667eea;
      padding-left: 12px;
      margin-left: 0;
      color: #cccccc;
      font-style: italic;
    }

    :deep(a) {
      color: #667eea;
      text-decoration: none;
    }

    :deep(a:hover) {
      text-decoration: underline;
    }

    :deep(strong) {
      color: #ffffff;
      font-weight: 600;
    }

    :deep(em) {
      font-style: italic;
    }
  }

  .user-input-form {
    .form-grid {
      display: flex;
      flex-direction: column;
      gap: 16px;
      margin-bottom: 16px;

      @media (max-width: 768px) {
        gap: 12px;
      }
    }

    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;

      label {
        font-size: 13px;
        font-weight: 500;
        color: #ffffff;
      }

      .form-input {
        padding: 8px 12px;
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid rgba(255, 255, 255, 0.2);
        border-radius: 6px;
        color: #ffffff;
        font-size: 14px;
        transition: border-color 0.2s ease;

        &:focus {
          outline: none;
          border-color: #667eea;
          box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
        }

        &::placeholder {
          color: #888888;
        }
      }

      .form-textarea {
        resize: vertical;
        min-height: 60px;
        font-family: inherit;
      }

      .form-select {
        cursor: pointer;

        option {
          background: #2d3748;
          color: #ffffff;
        }
      }

      .checkbox-group,
      .radio-group {
        display: flex;
        flex-direction: column;
        gap: 8px;

        .checkbox-item,
        .radio-item {
          display: flex;
          align-items: center;
          gap: 8px;
          cursor: pointer;
          padding: 4px 0;

          .form-checkbox,
          .form-radio {
            width: 16px;
            height: 16px;
            margin: 0;
            cursor: pointer;
            accent-color: #667eea;
          }

          .checkbox-label,
          .radio-label {
            color: #ffffff;
            font-size: 14px;
            user-select: none;
          }
        }
      }
    }

    .submit-user-input-btn {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: #ffffff;
      border: none;
      padding: 10px 20px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s ease;

      &:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
      }

      &:active {
        transform: translateY(0);
      }
    }
  }
}
</style>

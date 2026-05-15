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
  <ConfigPanel>
    <template #title>
      <h2>{{ t('config.databaseCleanup.title') }}</h2>
    </template>

    <template #actions>
      <button class="action-btn refresh-btn" @click="loadCounts" :disabled="loading">
        <Icon icon="carbon:refresh" :class="{ spinning: loading }" />
        {{ t('config.databaseCleanup.refresh') }}
      </button>
      <button
        class="action-btn danger-btn"
        @click="showClearModal = true"
        :disabled="loading || isAllEmpty"
      >
        <Icon icon="carbon:trash-can" />
        {{ t('config.databaseCleanup.clearAll') }}
      </button>
    </template>

    <div class="content-wrapper">
      <div class="description-section">
        <p class="description-text">{{ t('config.databaseCleanup.description') }}</p>
      </div>

      <div class="tables-container">
        <div v-if="loading && !tableCounts" class="loading-state">
          <Icon icon="carbon:loading" class="loading-icon spinning" />
          {{ t('common.loading') }}
        </div>

        <div v-else-if="tableCounts" class="tables-list">
          <div v-for="table in tableList" :key="table.key" class="table-card">
            <div class="table-header">
              <h3 class="table-name">{{ table.name }}</h3>
              <div class="table-count-badge">
                {{ formatCount(tableCounts?.[table.key] || 0) }}
                {{ t('config.databaseCleanup.rows') }}
              </div>
            </div>
            <p class="table-description">{{ table.description }}</p>
          </div>
        </div>

        <div v-if="!loading && tableCounts && isAllEmpty" class="empty-state">
          <Icon icon="carbon:checkmark-filled" class="empty-icon" />
          <p>{{ t('config.databaseCleanup.allEmpty') }}</p>
        </div>
      </div>
    </div>
  </ConfigPanel>

  <!-- Clear Confirmation Modal -->
  <Modal v-model="showClearModal" :title="t('config.databaseCleanup.clearConfirm')">
    <div class="clear-modal-content">
      <p class="warning-text">{{ t('config.databaseCleanup.clearMessage') }}</p>
      <ul class="tables-to-clear">
        <li v-for="table in tableList" :key="table.key">
          <strong>{{ table.name }}</strong>
          <span class="count-preview"
            >({{ formatCount(tableCounts?.[table.key] || 0) }}
            {{ t('config.databaseCleanup.rows') }})</span
          >
        </li>
      </ul>
    </div>
    <template #footer>
      <button class="btn-secondary" @click="showClearModal = false">
        {{ t('common.cancel') }}
      </button>
      <button class="btn-danger" @click="handleClearAll" :disabled="clearing">
        <Icon v-if="clearing" icon="carbon:loading" class="loading-icon spinning" />
        {{ t('config.databaseCleanup.clearAll') }}
      </button>
    </template>
  </Modal>
</template>

<script setup lang="ts">
import { DatabaseCleanupApiService, type TableCounts } from '@/api/database-cleanup-api-service'
import Modal from '@/components/modal/index.vue'
import { useToast } from '@/plugins/useToast'
import ConfigPanel from '@/views/configs/components/configPanel.vue'
import { Icon } from '@iconify/vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const toast = useToast()

// Reactive data
const tableCounts = ref<TableCounts | null>(null)
const loading = ref(false)
const clearing = ref(false)
const showClearModal = ref(false)

// Table metadata
const tableList = [
  {
    key: 'act_tool_info' as keyof TableCounts,
    name: 'act_tool_info',
    description: t('config.databaseCleanup.tableDescriptions.actToolInfo'),
  },
  {
    key: 'think_act_record' as keyof TableCounts,
    name: 'think_act_record',
    description: t('config.databaseCleanup.tableDescriptions.thinkActRecord'),
  },
  {
    key: 'plan_execution_record' as keyof TableCounts,
    name: 'plan_execution_record',
    description: t('config.databaseCleanup.tableDescriptions.planExecutionRecord'),
  },
  {
    key: 'agent_execution_record' as keyof TableCounts,
    name: 'agent_execution_record',
    description: t('config.databaseCleanup.tableDescriptions.agentExecutionRecord'),
  },
  {
    key: 'ai_chat_memory' as keyof TableCounts,
    name: 'ai_chat_memory',
    description: t('config.databaseCleanup.tableDescriptions.aiChatMemory'),
  },
]

// Computed properties
const isAllEmpty = computed(() => {
  if (!tableCounts.value) return false
  return Object.values(tableCounts.value).every(count => count === 0)
})

/**
 * Format count with commas
 */
const formatCount = (count: number): string => {
  return count.toLocaleString()
}

/**
 * Load table counts
 */
const loadCounts = async () => {
  loading.value = true
  try {
    tableCounts.value = await DatabaseCleanupApiService.getTableCounts()
  } catch (error) {
    console.error('Failed to load table counts:', error)
    toast.error(
      t('config.databaseCleanup.refreshFailed') +
        ': ' +
        (error instanceof Error ? error.message : String(error))
    )
  } finally {
    loading.value = false
  }
}

/**
 * Handle clear all tables
 */
const handleClearAll = async () => {
  clearing.value = true
  try {
    await DatabaseCleanupApiService.clearAllTables()
    toast.success(t('config.databaseCleanup.clearSuccess'))
    showClearModal.value = false
    // Reload counts to show updated values
    await loadCounts()
  } catch (error) {
    console.error('Failed to clear tables:', error)
    toast.error(
      t('config.databaseCleanup.clearFailed') +
        ': ' +
        (error instanceof Error ? error.message : String(error))
    )
  } finally {
    clearing.value = false
  }
}

// Load counts on mount
onMounted(() => {
  loadCounts()
})
</script>

<style scoped>
.content-wrapper {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.description-section {
  margin-bottom: 24px;
  padding: 16px;
  background: rgba(244, 67, 54, 0.1);
  border: 1px solid rgba(244, 67, 54, 0.2);
  border-radius: 8px;
  flex-shrink: 0;
}

.description-text {
  margin: 0;
  color: rgba(255, 255, 255, 0.8);
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-line;
}

.tables-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.tables-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.table-card {
  padding: 20px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  transition: all 0.3s ease;
}

.table-card:hover {
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.15);
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.table-name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
  font-family: monospace;
}

.table-count-badge {
  padding: 6px 12px;
  background: rgba(102, 126, 234, 0.2);
  border: 1px solid rgba(102, 126, 234, 0.4);
  border-radius: 6px;
  font-size: 16px;
  font-weight: 600;
  color: #667eea;
  font-family: monospace;
}

.table-description {
  margin: 0;
  color: rgba(255, 255, 255, 0.7);
  font-size: 14px;
  line-height: 1.6;
}

.action-btn {
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.3s ease;
}

.action-btn.refresh-btn {
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.8);
}

.action-btn.refresh-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.25);
  color: rgba(255, 255, 255, 0.95);
}

.action-btn.danger-btn {
  background: rgba(244, 67, 54, 0.2);
  border: 1px solid rgba(244, 67, 54, 0.4);
  color: #ef5350;
}

.action-btn.danger-btn:hover:not(:disabled) {
  background: rgba(244, 67, 54, 0.3);
  border-color: rgba(244, 67, 54, 0.6);
  transform: translateY(-1px);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none !important;
}

.loading-state,
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: rgba(255, 255, 255, 0.6);
}

.loading-icon,
.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.5;
  color: rgba(255, 255, 255, 0.4);
}

.empty-icon {
  color: #4caf50;
  opacity: 1;
}

.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.clear-modal-content {
  padding: 20px 0;
}

.warning-text {
  margin: 0 0 20px 0;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  line-height: 1.6;
}

.tables-to-clear {
  margin: 0;
  padding-left: 20px;
  color: rgba(255, 255, 255, 0.8);
  font-size: 14px;
}

.tables-to-clear li {
  margin-bottom: 12px;
  line-height: 1.6;
}

.tables-to-clear strong {
  color: rgba(255, 255, 255, 0.9);
  font-family: monospace;
}

.count-preview {
  color: rgba(255, 255, 255, 0.6);
  margin-left: 8px;
}

.btn-secondary,
.btn-danger {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease;
}

.btn-secondary {
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.8);
}

.btn-secondary:hover {
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.25);
  color: rgba(255, 255, 255, 0.95);
}

.btn-danger {
  background: rgba(244, 67, 54, 0.2);
  border: 1px solid rgba(244, 67, 54, 0.4);
  color: #ef5350;
}

.btn-danger:hover:not(:disabled) {
  background: rgba(244, 67, 54, 0.3);
  border-color: rgba(244, 67, 54, 0.6);
  transform: translateY(-1px);
}

.btn-danger:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none !important;
}
</style>

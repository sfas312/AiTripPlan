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
  <div class="assigned-tools">
    <div class="section-header">
      <span>{{ title }} ({{ allSelectedToolIds.length }})</span>
      <button class="action-btn small" @click="$emit('add-tools')" v-if="showAddButton">
        <Icon icon="carbon:add" />
        {{ addButtonText }}
      </button>
    </div>

    <div
      class="tools-grid"
      :class="{
        'grid-layout': useGridLayout,
        'has-more-items': useGridLayout && allSelectedToolIds.length > 2,
      }"
    >
      <div
        v-for="toolId in allSelectedToolIds"
        :key="toolId"
        class="tool-item"
        :class="{
          assigned: isToolExists(toolId),
          'non-existent': !isToolExists(toolId),
        }"
        :title="getToolDescription(toolId)"
      >
        <div class="tool-info">
          <div class="tool-name-row">
            <span class="tool-name">{{ getToolDisplayNameWithGroup(toolId) }}</span>
            <Icon
              v-if="!isToolExists(toolId)"
              icon="carbon:warning"
              class="tool-warning-icon"
              :title="'Tool not found: ' + toolId"
            />
          </div>
          <span v-if="isToolExists(toolId)" class="tool-desc" :title="getToolDescription(toolId)">
            {{ getToolDescription(toolId) }}
          </span>
          <span v-else class="tool-warning-message">
            {{ t('sidebar.toolNotExistWarning') }}
          </span>
        </div>
      </div>

      <div v-if="allSelectedToolIds.length === 0" class="no-tools">
        <Icon icon="carbon:tool-box" />
        <span>{{ emptyText }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useAvailableToolsSingleton } from '@/composables/useAvailableTools'
import { Icon } from '@iconify/vue'
import { computed, onMounted, watch, ref } from 'vue'
import { useI18n } from 'vue-i18n'

// I18n
const { t } = useI18n()

// Props
interface Props {
  title: string
  selectedToolIds: string[]
  addButtonText?: string
  emptyText?: string
  showAddButton?: boolean
  useGridLayout?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  addButtonText: 'Add/Remove Tools',
  emptyText: 'No tools assigned',
  showAddButton: true,
  useGridLayout: false,
})

// Emits
const emit = defineEmits<{
  'add-tools': []
  'tools-filtered': [filteredToolIds: string[]]
}>()

// Get available tools from singleton
const availableToolsStore = useAvailableToolsSingleton()
const availableTools = computed(() => availableToolsStore.availableTools.value)

// Load available tools on mount if not already loaded
onMounted(() => {
  if (availableTools.value.length === 0 && !availableToolsStore.isLoading.value) {
    availableToolsStore.loadAvailableTools()
  }
})

// Computed property to return all selected tool IDs without filtering
const allSelectedToolIds = computed(() => {
  if (!Array.isArray(props.selectedToolIds)) {
    return []
  }
  return props.selectedToolIds
})

// Helper method to check if a tool exists in availableTools
const isToolExists = (toolId: string): boolean => {
  return availableTools.value.some(tool => tool.key === toolId)
}

// Track last emitted filtered tools to prevent duplicate emissions
const lastEmittedFilteredTools = ref<string[]>([])

// Watch for changes in available tools and emit filtered tools event
// This maintains backward compatibility for components that listen to tools-filtered
// Only watch availableTools to avoid recursive updates when selectedToolIds changes
watch(
  availableTools,
  () => {
    if (!Array.isArray(props.selectedToolIds)) {
      return
    }
    // Emit only existing tools for backward compatibility
    const existingTools = props.selectedToolIds.filter(toolId => isToolExists(toolId))

    // Only emit if the filtered list actually changed to prevent recursive updates
    const existingToolsStr = existingTools.sort().join(',')
    const lastEmittedStr = lastEmittedFilteredTools.value.sort().join(',')

    if (existingToolsStr !== lastEmittedStr) {
      lastEmittedFilteredTools.value = existingTools
      emit('tools-filtered', existingTools)
    }
  },
  { immediate: true }
)

// Methods
const getToolDisplayNameWithGroup = (toolId: string): string => {
  const tool = availableTools.value.find(t => t.key === toolId)
  if (!tool) return toolId

  // Use the tool.key directly as it already contains the correct format (serviceGroup-toolName)
  // The backend returns keys in serviceGroup-toolName format with hyphens
  return tool.key
}

const getToolDescription = (toolId: string): string => {
  const tool = availableTools.value.find(t => t.key === toolId)
  return tool ? tool.description : ''
}
</script>

<style scoped>
.assigned-tools {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.section-header span {
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 6px;
  color: #fff;
  cursor: pointer;
  transition: all 0.3s ease;
  font-size: 14px;
}

.action-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-btn.small {
  padding: 6px 10px;
  font-size: 11px;
}

.tools-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tools-grid.grid-layout {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  max-height: calc(2 * (var(--tool-item-height, 80px) + 12px));
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  scrollbar-width: thin;
  scrollbar-color: rgba(102, 126, 234, 0.5) transparent;
}

.tools-grid.grid-layout::-webkit-scrollbar {
  width: 6px;
}

.tools-grid.grid-layout::-webkit-scrollbar-track {
  background: transparent;
}

.tools-grid.grid-layout::-webkit-scrollbar-thumb {
  background: rgba(102, 126, 234, 0.5);
  border-radius: 3px;
}

.tools-grid.grid-layout::-webkit-scrollbar-thumb:hover {
  background: rgba(102, 126, 234, 0.7);
}

.tools-grid.grid-layout::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 20px;
  background: linear-gradient(to bottom, transparent, rgba(0, 0, 0, 0.3));
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.3s ease;
}

.tools-grid.grid-layout.has-more-items::after {
  opacity: 1;
}

.tool-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  transition: all 0.3s ease;
  --tool-item-height: 80px;
}

.tools-grid.grid-layout .tool-item {
  padding: 12px;
  justify-content: flex-start;
}

.tool-item:hover {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.2);
}

.tool-item.assigned {
  border-color: rgba(102, 126, 234, 0.3);
  background: rgba(102, 126, 234, 0.1);
}

.tool-item.non-existent {
  border-color: rgba(251, 191, 36, 0.4);
  background: rgba(251, 191, 36, 0.1);
}

.tool-item.non-existent:hover {
  border-color: rgba(251, 191, 36, 0.6);
  background: rgba(251, 191, 36, 0.15);
}

.tool-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}

.tool-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.tool-warning-icon {
  width: 16px;
  height: 16px;
  color: rgba(251, 191, 36, 0.9);
  flex-shrink: 0;
}

.tool-warning-message {
  font-size: 12px;
  color: rgba(251, 191, 36, 0.8);
  font-style: italic;
  line-height: 1.3;
}

.tool-name {
  display: block;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
}

.tool-desc {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: help;
  position: relative;
}

.no-tools {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: rgba(255, 255, 255, 0.4);
  font-style: italic;
}

.tools-grid.grid-layout .no-tools {
  padding: 16px;
  font-size: 12px;
  grid-column: 1 / -1;
}

.no-tools svg {
  width: 20px;
  height: 20px;
  opacity: 0.5;
}
</style>

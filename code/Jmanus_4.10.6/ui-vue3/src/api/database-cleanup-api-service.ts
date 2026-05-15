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

/**
 * Table count response type
 */
export interface TableCounts {
  act_tool_info: number
  think_act_record: number
  plan_execution_record: number
  agent_execution_record: number
  ai_chat_memory: number
}

/**
 * Database Cleanup API service class
 */
export class DatabaseCleanupApiService {
  private static readonly BASE_URL = '/api/database-cleanup'

  /**
   * Get row counts for all monitored tables
   */
  public static async getTableCounts(): Promise<TableCounts> {
    const response = await fetch(`${this.BASE_URL}/counts`)
    if (!response.ok) {
      throw new Error(`Failed to fetch table counts: ${response.statusText}`)
    }
    return await response.json()
  }

  /**
   * Clear all rows from monitored tables
   */
  public static async clearAllTables(): Promise<TableCounts> {
    const response = await fetch(`${this.BASE_URL}/clear`, {
      method: 'DELETE',
    })
    if (!response.ok) {
      throw new Error(`Failed to clear tables: ${response.statusText}`)
    }
    return await response.json()
  }
}

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
package com.alibaba.cloud.ai.lynxe.config.service;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.cloud.ai.lynxe.recorder.repository.ActToolInfoRepository;
import com.alibaba.cloud.ai.lynxe.recorder.repository.AgentExecutionRecordRepository;
import com.alibaba.cloud.ai.lynxe.recorder.repository.PlanExecutionRecordRepository;
import com.alibaba.cloud.ai.lynxe.recorder.repository.ThinkActRecordRepository;

/**
 * Service for database cleanup operations
 */
@Service
public class DatabaseCleanupService {

	private static final Logger logger = LoggerFactory.getLogger(DatabaseCleanupService.class);

	private static final String AI_CHAT_MEMORY_TABLE = "ai_chat_memory";

	@Autowired
	private ActToolInfoRepository actToolInfoRepository;

	@Autowired
	private ThinkActRecordRepository thinkActRecordRepository;

	@Autowired
	private AgentExecutionRecordRepository agentExecutionRecordRepository;

	@Autowired
	private PlanExecutionRecordRepository planExecutionRecordRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Get row counts for all monitored tables
	 * @return Map with table names as keys and row counts as values
	 */
	public Map<String, Long> getTableCounts() {
		Map<String, Long> counts = new HashMap<>();

		try {
			// Count rows from JPA repositories
			counts.put("act_tool_info", actToolInfoRepository.count());
			counts.put("think_act_record", thinkActRecordRepository.count());
			counts.put("agent_execution_record", agentExecutionRecordRepository.count());
			counts.put("plan_execution_record", planExecutionRecordRepository.count());

			// Count rows from ai_chat_memory using JdbcTemplate
			Long chatMemoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + AI_CHAT_MEMORY_TABLE,
					Long.class);
			counts.put("ai_chat_memory", chatMemoryCount != null ? chatMemoryCount : 0L);

			logger.debug("Retrieved table counts: {}", counts);
		}
		catch (Exception e) {
			logger.error("Failed to get table counts", e);
			throw new RuntimeException("Failed to get table counts: " + e.getMessage(), e);
		}

		return counts;
	}

	/**
	 * Clear all rows from monitored tables Deletes in order to respect foreign key
	 * constraints: 1. act_tool_info (child of think_act_record) 2. think_act_record
	 * (child of agent_execution_record) 3. agent_execution_record (child of
	 * plan_execution_record) 4. plan_execution_record (parent) 5. ai_chat_memory
	 * (standalone)
	 * @return Map with table names as keys and number of deleted rows as values
	 */
	@Transactional
	public Map<String, Long> clearAllTables() {
		Map<String, Long> deletedCounts = new HashMap<>();

		try {
			logger.info("Starting database cleanup - clearing all monitored tables");

			// Delete in order to respect foreign key constraints
			// 1. Delete act_tool_info first (child table)
			long actToolInfoCount = actToolInfoRepository.count();
			actToolInfoRepository.deleteAll();
			deletedCounts.put("act_tool_info", actToolInfoCount);
			logger.info("Deleted {} rows from act_tool_info", actToolInfoCount);

			// 2. Delete think_act_record (child of agent_execution_record)
			long thinkActRecordCount = thinkActRecordRepository.count();
			thinkActRecordRepository.deleteAll();
			deletedCounts.put("think_act_record", thinkActRecordCount);
			logger.info("Deleted {} rows from think_act_record", thinkActRecordCount);

			// 3. Delete agent_execution_record (child of plan_execution_record)
			long agentExecutionRecordCount = agentExecutionRecordRepository.count();
			agentExecutionRecordRepository.deleteAll();
			deletedCounts.put("agent_execution_record", agentExecutionRecordCount);
			logger.info("Deleted {} rows from agent_execution_record", agentExecutionRecordCount);

			// 4. Delete plan_execution_record (parent table)
			long planExecutionRecordCount = planExecutionRecordRepository.count();
			planExecutionRecordRepository.deleteAll();
			deletedCounts.put("plan_execution_record", planExecutionRecordCount);
			logger.info("Deleted {} rows from plan_execution_record", planExecutionRecordCount);

			// 5. Delete ai_chat_memory (standalone table)
			Long chatMemoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + AI_CHAT_MEMORY_TABLE,
					Long.class);
			if (chatMemoryCount != null && chatMemoryCount > 0) {
				jdbcTemplate.update("DELETE FROM " + AI_CHAT_MEMORY_TABLE);
				deletedCounts.put("ai_chat_memory", chatMemoryCount);
				logger.info("Deleted {} rows from ai_chat_memory", chatMemoryCount);
			}
			else {
				deletedCounts.put("ai_chat_memory", 0L);
			}

			logger.info("Database cleanup completed successfully. Deleted rows: {}", deletedCounts);
		}
		catch (Exception e) {
			logger.error("Failed to clear tables", e);
			throw new RuntimeException("Failed to clear tables: " + e.getMessage(), e);
		}

		return deletedCounts;
	}

}

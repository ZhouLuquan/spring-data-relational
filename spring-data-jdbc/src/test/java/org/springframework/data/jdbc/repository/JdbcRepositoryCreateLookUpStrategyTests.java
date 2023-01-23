/*
 * Copyright 2022-2023 the original author or authors.
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
package org.springframework.data.jdbc.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.support.JdbcRepositoryFactory;
import org.springframework.data.jdbc.testing.TestConfiguration;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test to verify that <code>@EnableJdbcRepositories(queryLookupStrategy = QueryLookupStrategy.Key.CREATE)</code> works
 * as intended.
 *
 * @author Diego Krupitza
 * @author Jens Schauder
 */
@ContextConfiguration
@Transactional
@ExtendWith(SpringExtension.class)
class JdbcRepositoryCreateLookUpStrategyTests extends AbstractJdbcRepositoryLookUpStrategyTests {

	@Test // GH-1043
	void declaredQueryShouldWork() {
		onesRepository.deleteAll();

		// here the declared query will use the derived query which does something totally different
		callDeclaredQuery("D", 0);
	}

	@Test // GH-1043
	void derivedQueryShouldWork() {
		onesRepository.deleteAll();
		callDerivedQuery();
	}

	@Configuration
	@Import(TestConfiguration.class)
	@EnableJdbcRepositories(considerNestedRepositories = true, queryLookupStrategy = QueryLookupStrategy.Key.CREATE,
			includeFilters = @ComponentScan.Filter(value = OnesRepository.class, type = FilterType.ASSIGNABLE_TYPE))
	static class Config {

		@Autowired JdbcRepositoryFactory factory;

		@Bean
		Class<?> testClass() {
			return AbstractJdbcRepositoryLookUpStrategyTests.class;
		}
	}

}
/*
 * Copyright 2022 the original author or authors.
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
package org.springframework.data.jdbc.core.convert.sqlgeneration;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Builds the structure of an analytic query. The structure contains arbitrary objects for tables and columns
 */
class AnalyticStructureBuilder<T, C> {

	private Select table;

	AnalyticStructureBuilder<T, C> addTable(T table, Function<TableDefinition, TableDefinition> tableDefinitionConfiguration) {

		this.table = createTable(table, tableDefinitionConfiguration);

		return this;
	}

	AnalyticStructureBuilder<T, C> addChildTo(T parent, T child, Function<TableDefinition, TableDefinition> tableDefinitionConfiguration){

		this.table = new AnalyticJoin(table, createTable(child, tableDefinitionConfiguration));

		return this;
	}

	List<? extends AnalyticColumn> getColumns() {

		return table.getColumns();
	}

	AnalyticColumn getIdColumn() {
		return table.getId();
	}

	private TableDefinition createTable(T table, Function<TableDefinition, TableDefinition> tableDefinitionConfiguration) {
		return tableDefinitionConfiguration.apply( new TableDefinition(table));
	}


	abstract class Select{

		abstract List<? extends AnalyticColumn> getColumns();

		abstract AnalyticColumn getId();
	}

	class TableDefinition extends Select{

		private final T table;
		private final AnalyticColumn id;
		private final List<? extends AnalyticColumn> columns;

		TableDefinition(T table, @Nullable AnalyticColumn id, List<? extends AnalyticColumn> columns) {

			this.table = table;
			this.id = id;
			this.columns = Collections.unmodifiableList(columns);
		}

		TableDefinition(T table) {
			this(table, null, Collections.emptyList());
		}

		TableDefinition withId(C id) {
			return new TableDefinition(table, new BaseColumn(id), columns);
		}

		TableDefinition withColumns(C... columns) {

			return new TableDefinition(table, id, Arrays.stream(columns).map(BaseColumn::new).toList());
		}

		@Override
		public List<? extends AnalyticColumn> getColumns() {

			return columns;
		}

		@Override
		public AnalyticColumn getId() {
			return id;
		}
	}

	abstract class AnalyticColumn {
		abstract C getColumn();
	}

	class BaseColumn extends AnalyticColumn{

		final C column;

		BaseColumn(C column) {
			this.column = column;
		}

		@Override
		C getColumn() {
			return column;
		}
	}

	class DerivedColumn extends AnalyticColumn{

		final AnalyticColumn column;

		DerivedColumn(AnalyticColumn column) {
			this.column = column;
		}

		@Override
		C getColumn() {
			return column.getColumn();
		}
	}

	private class AnalyticJoin extends Select {

		private final Select parent;
		private final Select child;

		AnalyticJoin(Select parent, Select child) {

			this.parent = parent;
			this.child = child;
		}

		@Override
		public List<? extends AnalyticColumn> getColumns() {

			List<AnalyticColumn> result = new ArrayList<>();
			parent.getColumns().forEach(c -> result.add(new DerivedColumn(c)));
			child.getColumns().forEach(c -> result.add(new DerivedColumn(c)));

			return result;
		}

		@Override
		public AnalyticColumn getId() {
			return new DerivedColumn(parent.getId());
		}
	}
}
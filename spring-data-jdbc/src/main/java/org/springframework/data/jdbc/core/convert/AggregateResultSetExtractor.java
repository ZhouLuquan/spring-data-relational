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
package org.springframework.data.jdbc.core.convert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mapping.Parameter;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.model.EntityInstantiator;
import org.springframework.data.mapping.model.ParameterValueProvider;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/*
Implementation alternatives:
 - Instead of "resetting" readers one could simply create a new instance.
 - Move peek logic in CachingResulSet.
 */
class AggregateResultSetExtractor<T> implements org.springframework.jdbc.core.ResultSetExtractor<Iterable<T>> {

	private static final Log log = LogFactory.getLog(AggregateResultSetExtractor.class);

	private final RelationalMappingContext context;
	private final RelationalPersistentEntity<T> rootEntity;
	private final JdbcConverter converter;
	private final PathToColumnMapping propertyToColumn;
	private final PersistentPathUtil persistentPathUtil;

	AggregateResultSetExtractor(RelationalMappingContext context, RelationalPersistentEntity<T> rootEntity,
			JdbcConverter converter, PathToColumnMapping pathToColumn) {

		this.context = context;
		this.rootEntity = rootEntity;
		this.converter = converter;
		this.propertyToColumn = pathToColumn;
		this.persistentPathUtil = new PersistentPathUtil(context, rootEntity);
	}

	@Override
	public Iterable<T> extractData(ResultSet resultSet) throws SQLException, DataAccessException {

		CachingResultSet crs = new CachingResultSet(resultSet);

		CollectionReader reader = new CollectionReader(crs);

		while (crs.next()) {
			reader.read();
		}

		return (Iterable<T>) reader.getResultAndReset();
	}

	/**
	 * create an instance and populate all its properties
	 */
	private Object hydrateInstance(EntityInstantiator instantiator, ResultSetParameterValueProvider valueProvider,
			RelationalPersistentEntity<?> entity) {

		if (valueProvider.basePath != null && // this is a nested ValueProvider
		valueProvider.basePath.getLeafProperty().isEmbedded() && // it's an embedded
		!valueProvider.basePath.getLeafProperty().shouldCreateEmptyEmbedded() &&
		!valueProvider.hasValue()) { // all values have been null
			return null;
		}

		Object instance = instantiator.createInstance(entity, valueProvider);

		PersistentPropertyAccessor<?> accessor = entity.getPropertyAccessor(instance);
		if (true) {
			accessor = new DebuggingPersistentPropertyAccessor(accessor);

		}
		final PersistentPropertyAccessor<?> finalAccessor = accessor;

		if (entity.requiresPropertyPopulation()) {
			entity.doWithProperties((PropertyHandler<RelationalPersistentProperty>) p -> {
				if (!entity.isCreatorArgument(p)) {
					finalAccessor.setProperty(p, valueProvider.getValue(p));
				}
			});
		}
		return instance;
	}

	private interface Reader {

		void read();

		boolean hasResult();

		Object getResultAndReset();
	}

	private static class MapAdapter extends AbstractCollection<Map.Entry<Object, Object>> {

		private Map<Object, Object> map = new HashMap<>();

		@Override
		public Iterator<Map.Entry<Object, Object>> iterator() {
			return map.entrySet().iterator();
		}

		@Override
		public int size() {
			return map.size();
		}

		@Override
		public boolean add(Map.Entry<Object, Object> entry) {

			map.put(entry.getKey(), entry.getValue());
			return true;
		}
	}

	private static class ListAdapter extends AbstractCollection<Map.Entry<Object, Object>> {

		private List<Object> list = new ArrayList<>();

		@Override
		public Iterator<Map.Entry<Object, Object>> iterator() {
			throw new UnsupportedOperationException("Do we need this?");
		}

		@Override
		public int size() {
			return list.size();
		}

		@Override
		public boolean add(Map.Entry<Object, Object> entry) {

			Integer index = (Integer) entry.getKey();
			while (index >= list.size()){
				list.add(null);
			}
			list.set(index, entry.getValue());
			return true;
		}
	}

	private class EntityReader implements Reader {

		// for debugging only
		private final String name;

		private final PersistentPropertyPath<RelationalPersistentProperty> basePath;
		private final CachingResultSet crs;

		private final EntityInstantiator instantiator;
		@Nullable private final String idColumn;

		private ResultSetParameterValueProvider valueProvider;
		private boolean result;

		Object oldId = null;

		private EntityReader(@Nullable PersistentPropertyPath<RelationalPersistentProperty> basePath, CachingResultSet crs) {
			this(basePath, crs, null);
		}

		private EntityReader(@Nullable PersistentPropertyPath<RelationalPersistentProperty> basePath, CachingResultSet crs,
				@Nullable String keyColumn) {

			this.basePath = basePath;
			this.crs = crs;

			RelationalPersistentEntity<?> entity = basePath == null ? rootEntity
					: context.getRequiredPersistentEntity(basePath.getLeafProperty().getActualType());
			instantiator = converter.getEntityInstantiators().getInstantiatorFor(entity);

			idColumn = entity.hasIdProperty()
					? propertyToColumn.column(persistentPathUtil.extend(basePath, entity.getRequiredIdProperty()))
					: keyColumn;
			;

			reset();

			name = "EntityReader for " + (basePath == null ? "<root>" : basePath.toDotPath());
		}

		@Override
		public void read() {

			if (idColumn != null && oldId == null) {
				oldId = crs.getObject(idColumn);
			}

			valueProvider.readValues();
			if (idColumn == null) {
				result = true;
			} else {
				Object peekedId = crs.peek(idColumn);
				if (peekedId == null || peekedId != oldId) {

					result = true;
					oldId = peekedId;
				}
			}
		}

		@Override
		public boolean hasResult() {
			return result;
		}

		@Override
		public Object getResultAndReset() {

			try {
				return hydrateInstance(instantiator, valueProvider, valueProvider.baseEntity);
			} finally {

				reset();
			}
		}

		private void reset() {

			valueProvider = new ResultSetParameterValueProvider(crs, basePath);
			result = false;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	class CollectionReader implements Reader {

		// debugging only
		private final String name;

		private final Supplier<Collection> collectionInitializer;
		private final Reader entityReader;

		private Collection result;

		private static Supplier<Collection> collectionInitializerFor(
				PersistentPropertyPath<RelationalPersistentProperty> path) {

			RelationalPersistentProperty property = path.getRequiredLeafProperty();
			if (List.class.isAssignableFrom(property.getType())) {
				return ListAdapter::new;
			} else if (property.isMap()) {
				return MapAdapter::new;
			} else {
				return HashSet::new;
			}
		}

		private CollectionReader(PersistentPropertyPath<RelationalPersistentProperty> basePath, CachingResultSet crs) {

			this.collectionInitializer = collectionInitializerFor(basePath);

			String keyColumn = null;
			final RelationalPersistentProperty property = basePath.getRequiredLeafProperty();
			if (property.isMap() || List.class.isAssignableFrom(basePath.getRequiredLeafProperty().getType())) {
				keyColumn = propertyToColumn.keyColumn(basePath);
			}

			if (property.isQualified()) {
				this.entityReader = new EntryReader(basePath, crs, keyColumn, property.getQualifierColumnType());
			} else {
				this.entityReader = new EntityReader(basePath, crs, keyColumn);
			}
			reset();
			name = "Reader for " + basePath.toDotPath();
		}

		private CollectionReader(CachingResultSet crs) {

			this.collectionInitializer = ArrayList::new;
			this.entityReader = new EntityReader(null, crs);
			reset();

			name = "Collectionreader for <root>";

		}

		@Override
		public void read() {

			entityReader.read();
			if (entityReader.hasResult()) {
				result.add(entityReader.getResultAndReset());
			}
		}

		@Override
		public boolean hasResult() {
			return false;
		}

		@Override
		public Object getResultAndReset() {

			try {
				if (result instanceof MapAdapter) {
					return ((MapAdapter) result).map;
				}
				if (result instanceof ListAdapter) {
					return ((ListAdapter) result).list;
				}
				return result;
			} finally {
				reset();
			}
		}

		private void reset() {
			result = collectionInitializer.get();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private class EntryReader implements Reader {

		final EntityReader delegate;
		final String keyColumn;
		private final TypeInformation<?> keyColumnType;

		Object key;

		EntryReader(PersistentPropertyPath<RelationalPersistentProperty> basePath, CachingResultSet crs, String keyColumn, Class<?> keyColumnType) {

			this.keyColumnType = TypeInformation.of( keyColumnType);
			this.delegate = new EntityReader(basePath, crs, keyColumn);
			this.keyColumn = keyColumn;
		}

		@Override
		public void read() {

			if (key == null) {
				Object unconvertedKeyObject = delegate.crs.getObject(keyColumn);
				key = converter.readValue( unconvertedKeyObject, keyColumnType);
			}
			delegate.read();
		}

		@Override
		public boolean hasResult() {
			return delegate.hasResult();
		}

		@Override
		public Object getResultAndReset() {

			try {
				return new AbstractMap.SimpleEntry<>(key, delegate.getResultAndReset());
			} finally {
				key = null;
			}
		}
	}

	private class ResultSetParameterValueProvider implements ParameterValueProvider<RelationalPersistentProperty> {

		private final CachingResultSet rs;
		/**
		 * The path which is used to determine columnNames
		 */
		private final PersistentPropertyPath<RelationalPersistentProperty> basePath;
		private final RelationalPersistentEntity<? extends Object> baseEntity;
		private Map<RelationalPersistentProperty, Object> aggregatedValues = new HashMap<>();

		ResultSetParameterValueProvider(CachingResultSet rs,
				PersistentPropertyPath<RelationalPersistentProperty> basePath) {

			this.rs = rs;
			this.basePath = basePath;
			this.baseEntity = basePath == null ? rootEntity
					: context.getRequiredPersistentEntity(basePath.getRequiredLeafProperty().getActualType());
		}

		@SuppressWarnings("unchecked")
		@Override
		@Nullable
		public <S> S getParameterValue(Parameter<S, RelationalPersistentProperty> parameter) {

			return (S) getValue(baseEntity.getRequiredPersistentProperty(parameter.getName()));
		}

		@Nullable
		private Object getValue(RelationalPersistentProperty property) {

			Object value = aggregatedValues.get(property);

			if (value instanceof Reader) {
				return ((Reader) value).getResultAndReset();
			}

			value = converter.readValue(value, property.getTypeInformation());

			return value;
		}

		/**
		 * read values for all collection like properties and aggregate them in a collection.
		 */
		void readValues() {
			baseEntity.forEach(this::readValue);
		}

		private void readValue(RelationalPersistentProperty p) {

			if (p.isEntity()) {

				Reader reader = null;

				if (p.isCollectionLike() || p.isMap()) { // even when there are no values we still want a (empty) collection.

					reader = (Reader) aggregatedValues.computeIfAbsent(p,
							pp -> new CollectionReader(persistentPathUtil.extend(basePath, pp), rs));
				}
				if (getIndicatorOf(p) != null) {

					if (!(p.isCollectionLike() || p.isMap())) { // for single entities we want a null entity instead of on filled with null values.

						reader = (Reader) aggregatedValues.computeIfAbsent(p,
								pp -> new EntityReader(persistentPathUtil.extend(basePath, pp), rs));
					}

					Assert.state(reader != null, "reader must not be null");

					reader.read();
				}
			} else {
				aggregatedValues.computeIfAbsent(p, this::getObject);
			}
		}

		@Nullable
		private Object getIndicatorOf(RelationalPersistentProperty p) {
			if (p.isMap() || List.class.isAssignableFrom(p.getType())) {
				return rs.getObject(getKeyName(p));
			}

			if (p.isEmbedded()) {
				return true;
			}

			return rs.getObject(getColumnName(p));
		}

		/**
		 * Obtain a single columnValue from the resultset without throwing an exception. If the column does not exist a null
		 * value is returned. Does not instantiate complex objects.
		 *
		 * @param property
		 * @return
		 */
		@Nullable
		private Object getObject(RelationalPersistentProperty property) {
			return rs.getObject(getColumnName(property));
		}

		/**
		 * converts a property into a column name representing that property.
		 *
		 * @param property
		 * @return
		 */
		private String getColumnName(RelationalPersistentProperty property) {

			return propertyToColumn.column(persistentPathUtil.extend(basePath, property));
		}

		private String getKeyName(RelationalPersistentProperty property) {

			return propertyToColumn.keyColumn(persistentPathUtil.extend(basePath, property));
		}
		
		private boolean hasValue(){

			for (Object value : aggregatedValues.values()) {
				if (value != null) {
					return true;
				}
			}
			return false;
		}
	}

	private class DebuggingPersistentPropertyAccessor<T> implements PersistentPropertyAccessor<T> {
		private final PersistentPropertyAccessor<?> delegate;

		DebuggingPersistentPropertyAccessor(PersistentPropertyAccessor<?> accessor) {
			this.delegate = accessor;
		}

		@Override
		public void setProperty(PersistentProperty<?> property, Object value) {
			
			try {

				Object convertedValue = converter.readValue(value, property.getTypeInformation());
				delegate.setProperty(property, convertedValue);
			} catch (Exception e) {
				throw new RuntimeException("Failed to set value %s on property %s for %s".formatted(value, property, getBean()),
						e);
			}
		}

		@Override
		public Object getProperty(PersistentProperty<?> property) {
			return delegate.getProperty(property);
		}

		@Override
		public T getBean() {
			return (T) delegate.getBean();
		}
	}

}
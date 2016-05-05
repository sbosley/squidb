/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the Apache 2.0 License.
 * See the accompanying LICENSE file for terms.
 */
package com.yahoo.squidb.data;

import com.yahoo.squidb.sql.Field;
import com.yahoo.squidb.sql.Property;
import com.yahoo.squidb.sql.Property.PropertyVisitor;
import com.yahoo.squidb.sql.Property.PropertyWritingVisitor;
import com.yahoo.squidb.sql.SqlTable;
import com.yahoo.squidb.sql.TableModelName;
import com.yahoo.squidb.utility.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Base class for models backed by a SQLite table or view. Attributes of a model are accessed and manipulated using
 * {@link Property} objects along with the {@link #get(Property) get} and {@link #set(Property, Object) set} methods.
 * <p>
 * Generated models automatically contain Property objects that correspond to the underlying columns in the table or
 * view as specified in their model spec definitions, and will have generated getter and setter methods for each
 * Property.
 * <p>
 * <h3>Data Source Ordering</h3>
 * When calling get(Property) or one of the generated getters, the model prioritizes values in the following order:
 * <ol>
 * <li>values explicitly set using set(Property, Object) or a generated setter, found in the set returned by
 * {@link #getSetValues()}</li>
 * <li>values written to the model as a result of fetching it using a {@link SquidDatabase} or constructing it from a
 * {@link SquidCursor}, found in the set returned by {@link #getDatabaseValues()}</li>
 * <li>default values, found in the set returned by {@link #getDefaultValues()}</li>
 * </ol>
 * If a value is not found in any of these places, an exception is thrown.
 * <p>
 * Transitory values (set using {@link #putTransitory(String, Object) putTransitory}) allow you to attach arbitrary
 * data that will not be saved to the database if you persist the model. Transitory values are not considered when
 * calling get(Property) or using generated getters; use {@link #getTransitory(String) getTransitory} to read these
 * values. Alternatively, use {@link #hasTransitory(String) checkTransitory} to merely check the presence of a
 * transitory value.
 * <p>
 * <h3>Interacting with Models</h3>
 * Models are usually created by fetching from a database or reading from a {@link SquidCursor} after querying a
 * database.
 *
 * <pre>
 * MyDatabase db = ...
 * Model model = db.fetch(Model.class, id, Model.PROPERTIES);
 * // or
 * SquidCursor&lt;Model&gt; cursor = db.query(Model.class, query);
 * cursor.moveToFirst();
 * Model model = new Model(cursor);
 * </pre>
 *
 * Models can also be instantiated in advance and populated with data from the current row of a SquidCursor.
 *
 * <pre>
 * model = new Model();
 * model.readPropertiesFromCursor(cursor);
 * </pre>
 *
 * @see com.yahoo.squidb.data.TableModel
 * @see com.yahoo.squidb.data.ViewModel
 */
public abstract class AbstractModel implements Cloneable {

    // --- static variables

    private static final ValuesStorageSavingVisitor saver = new ValuesStorageSavingVisitor();
    private static final ValuesStorageSavingVisitor otherTableSaver = new OtherTableValuesStorageSavingVisitor();

    private static final ValueCastingVisitor valueCastingVisitor = new ValueCastingVisitor();

    // --- abstract methods

    /** Get the full properties array for this model class */
    public abstract Property<?>[] getProperties();

    /** Get the TableModelName representing by this model class/table name pair */
    public abstract TableModelName getTableModelName();

    /** Get the default values for this object */
    public abstract ValuesStorage getDefaultValues();

    // --- data store variables and management

    protected final TableModelName tableModelName = getTableModelName();

    /** User set values */
    protected ValuesStorage setValues = null;

    /** Values from database */
    protected ValuesStorage values = null;

    /** Values from other tables (not persisted with model instances) */
    protected ValuesStorage otherTableValues = null;

    /** Transitory Metadata (not saved in database) */
    protected HashMap<String, Object> transitoryData = null;

    /** Get the database-read values for this object */
    public ValuesStorage getDatabaseValues() {
        return values;
    }

    /** Get the user-set values for this object */
    public ValuesStorage getSetValues() {
        return setValues;
    }

    /** Get a list of all field/value pairs merged across data sources */
    public ValuesStorage getMergedValues() {
        ValuesStorage mergedValues = newValuesStorage();

        ValuesStorage defaultValues = getDefaultValues();
        if (defaultValues != null) {
            mergedValues.putAll(defaultValues);
        }

        if (values != null) {
            mergedValues.putAll(values);
        }

        if (setValues != null) {
            mergedValues.putAll(setValues);
        }

        return mergedValues;
    }

    /** Get any values that have been read into this model but belong to some other table */
    public ValuesStorage getOtherTableValues() {
        return otherTableValues;
    }

    /**
     * This method should construct a new ValuesStorage object for the model instance to use. By default, this object
     * will be a {@link MapValuesStorage}, but other implementations can be used for other platforms if appropriate
     * by overriding this method.
     */
    protected ValuesStorage newValuesStorage() {
        return new MapValuesStorage();
    }

    /**
     * Clear all data on this model
     */
    public void clear() {
        values = null;
        setValues = null;
        otherTableValues = null;
        transitoryData = null;
    }

    /**
     * Transfers all set values into values. This usually occurs when a model is saved in the database so that future
     * saves will not need to write all the data again. Users should not usually need to call this method.
     */
    public void markSaved() {
        if (values == null) {
            values = setValues;
        } else if (setValues != null) {
            values.putAll(setValues);
        }
        setValues = null;
    }

    /**
     * Use merged values to compare two models to each other. Must be of exactly the same class.
     */
    @Override
    public boolean equals(Object other) {
        return other != null && getClass().equals(other.getClass()) && getMergedValues()
                .equals(((AbstractModel) other).getMergedValues());
    }

    @Override
    public int hashCode() {
        return getMergedValues().hashCode() ^ getClass().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "\n" +
                "set values:\n" + setValues + "\n" +
                "values:\n" + values + "\n";
    }

    @Override
    public AbstractModel clone() {
        AbstractModel clone;
        try {
            clone = (AbstractModel) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }

        if (setValues != null) {
            clone.setValues = newValuesStorage();
            clone.setValues.putAll(setValues);
        }

        if (values != null) {
            clone.values = newValuesStorage();
            clone.values.putAll(values);
        }

        if (otherTableValues != null) {
            clone.otherTableValues = newValuesStorage();
            clone.otherTableValues.putAll(otherTableValues);
        }
        return clone;
    }

    /**
     * Android-specific method for initializing object state from a Parcel object. The default implementation of this
     * method logs an error, as only its overridden version in AndroidTableModel and AndroidViewModel should ever be
     * called.
     *
     * @param source a Parcel object to read from
     */
    public void readFromParcel(Object source) {
        Logger.w(Logger.LOG_TAG, "Called readFromParcel on a non-parcelable model", new Throwable());
    }

    /**
     * @return true if this model has values that have been changed
     */
    public boolean isModified() {
        return setValues != null && setValues.size() > 0;
    }

    // --- data retrieval

    /**
     * Copies values from the given {@link ValuesStorage} into the model. The values will be added to the model as read
     * values (i.e. will not be considered set values or mark the model as dirty).
     *
     * @param values a key-value pairing of values to read from
     * @param properties which properties to read from the values. Only properties specified in this list will be read
     */
    public void readPropertiesFromValuesStorage(ValuesStorage values, Property<?>... properties) {
        prepareToReadProperties();

        if (values != null) {
            for (Property<?> property : properties) {
                String key = property.getNameForModelStorage(tableModelName);
                ValuesStorage valuesStorage;
                if (TableModelName.equals(property.tableModelName, tableModelName)) {
                    valuesStorage = this.values;
                } else {
                    valuesStorage = otherTableValues;
                }

                if (values.containsKey(key)) {
                    Object value = property.accept(valueCastingVisitor, values.get(key));
                    valuesStorage.put(key, value, true);
                }
            }
        }
    }

    /**
     * Copies values from the given Map. The values will be added to the model as read values (i.e. will not be
     * considered set values or mark the model as dirty).
     *
     * @param values a key-value pairing of values to read from
     * @param properties which properties to read from the values. Only properties specified in this list will be read
     */
    public void readPropertiesFromMap(Map<String, Object> values, Property<?>... properties) {
        if (values == null) {
            return;
        }
        readPropertiesFromValuesStorage(new MapValuesStorage(values), properties);
    }

    /**
     * Reads all properties from the supplied cursor into the model. This will clear any user-set values.
     */
    public void readPropertiesFromCursor(SquidCursor<?> cursor) {
        prepareToReadProperties();

        if (cursor != null) {
            for (Field<?> field : cursor.getFields()) {
                if (field instanceof Property<?>) {
                    readPropertyIntoModel(cursor, (Property<?>) field);
                }
            }
        }
    }

    /**
     * Reads the specified properties from the supplied cursor into the model. This will clear any user-set values.
     */
    public void readPropertiesFromCursor(SquidCursor<?> cursor, Property<?>... properties) {
        prepareToReadProperties();

        if (cursor != null) {
            for (Property<?> field : properties) {
                readPropertyIntoModel(cursor, field);
            }
        }
    }

    private void prepareToReadProperties() {
        if (values == null) {
            values = newValuesStorage();
        }

        if (otherTableValues == null) {
            otherTableValues = newValuesStorage();
        }

        // clears user-set values
        setValues = null;
        transitoryData = null;
    }

    private void readPropertyIntoModel(SquidCursor<?> cursor, Property<?> property) {
        try {
            ValuesStorage valuesStorage = values;
            ValuesStorageSavingVisitor valuesSaver = saver;
            if (!TableModelName.equals(property.tableModelName, tableModelName)) {
                valuesStorage = otherTableValues;
                valuesSaver = otherTableSaver;
            }
            if (cursor.has(property)) {
                valuesSaver.save(property, valuesStorage, cursor.get(property));
            }
        } catch (IllegalArgumentException e) {
            // underlying cursor may have changed, suppress
        }
    }

    /**
     * Return the value of the specified {@link Property}. The model prioritizes values as follows:
     * <ol>
     * <li>values explicitly set using {@link #set(Property, Object)} or a generated setter</li>
     * <li>values written to the model as a result of fetching it using a {@link SquidDatabase} or constructing it from
     * a {@link SquidCursor}</li>
     * <li>the set of default values as specified by {@link #getDefaultValues()}</li>
     * <li>values for properties that are not a part of this model class are stored separately, which will be
     * checked if and only if the given property is not native to this model class</li>
     * </ol>
     * If a value is not found in any of those places, an exception is thrown.
     *
     * @return the value of the specified property
     * @throws UnsupportedOperationException if the value is not found in the model
     */
    @SuppressWarnings("unchecked")
    public <TYPE> TYPE get(Property<TYPE> property) {
        return get(property, true);
    }

    /**
     * Return the value of the specified {@link Property}. The model prioritizes values as follows:
     * <ol>
     * <li>values explicitly set using {@link #set(Property, Object)} or a generated setter</li>
     * <li>values written to the model as a result of fetching it using a {@link SquidDatabase} or constructing it from
     * a {@link SquidCursor}</li>
     * <li>the set of default values as specified by {@link #getDefaultValues()}</li>
     * <li>values for properties that are not a part of this model class are stored separately, which will be
     * checked if and only if the given property is not native to this model class</li>
     * </ol>
     * If a value is not found in any of those places, an exception is thrown if throwIfNotFound is true, or null is
     * returned if it is false.
     *
     * @return the value of the specified property, or null if the value is not found and throwIfNotFound is false
     * @throws UnsupportedOperationException if the value is not found in the model and throwIfNotFound is true
     */
    public <TYPE> TYPE get(Property<TYPE> property, boolean throwIfNotFound) {
        String nameToUse = property.getNameForModelStorage(tableModelName);
        if (!TableModelName.equals(property.tableModelName, tableModelName)) {
            if (otherTableValues != null && otherTableValues.containsKey(nameToUse)) {
                return getFromValues(property, nameToUse, otherTableValues);
            }
        } else {
            if (setValues != null && setValues.containsKey(nameToUse)) {
                return getFromValues(property, nameToUse, setValues);
            } else if (values != null && values.containsKey(nameToUse)) {
                return getFromValues(property, nameToUse, values);
            } else if (getDefaultValues().containsKey(nameToUse)) {
                return getFromValues(property, nameToUse, getDefaultValues());
            }
        }

        if (throwIfNotFound) {
            throw new UnsupportedOperationException(nameToUse
                    + " not found in model. Make sure the value was set explicitly, read from a cursor,"
                    + " or that the model has a default value for this property.");
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <TYPE> TYPE getFromValues(Property<TYPE> property, String nameToUse, ValuesStorage values) {
        Object value = values.get(nameToUse);

        // Will throw a ClassCastException if the value could not be coerced to the correct type
        return (TYPE) property.accept(valueCastingVisitor, value);
    }

    /**
     * @param property the {@link Property} to check
     * @return true if a value for this property has been read from the database or set by the user
     */
    public boolean containsValue(Property<?> property) {
        if (TableModelName.equals(property.tableModelName, tableModelName)) {
            return valuesContainsKey(setValues, property) || valuesContainsKey(values, property);
        } else {
            return valuesContainsKey(otherTableValues, property);
        }
    }

    /**
     * @param property the {@link Property} to check
     * @return true if a value for this property has been read from the database or set by the user, and the value
     * stored is not null
     */
    public boolean containsNonNullValue(Property<?> property) {
        if (TableModelName.equals(property.tableModelName, tableModelName)) {
            return (valuesContainsKey(setValues, property) && setValues.get(property.getExpression()) != null)
                    || (valuesContainsKey(values, property) && values.get(property.getExpression()) != null);
        } else {
            return (valuesContainsKey(otherTableValues, property) &&
                    otherTableValues.get(property.getSelectName()) != null);
        }
    }

    /**
     * @param property the {@link Property} to check
     * @return true if this property has a value that was set by the user
     */
    public boolean fieldIsDirty(Property<?> property) {
        return TableModelName.equals(property.tableModelName, tableModelName) && valuesContainsKey(setValues, property);
    }

    private boolean valuesContainsKey(ValuesStorage values, Property<?> property) {
        return values != null && values.containsKey(property.getNameForModelStorage(tableModelName));
    }

    // --- data storage

    /**
     * Check whether the user has changed this property value and it should be stored for saving in the database
     */
    protected <TYPE> boolean shouldSaveValue(Property<TYPE> property, TYPE newValue) {
        return !TableModelName.equals(property.tableModelName, tableModelName) ||
                shouldSaveValue(property.getExpression(), newValue);
    }

    protected boolean shouldSaveValue(String name, Object newValue) {
        // we've already decided to save it, so overwrite old value
        if (setValues.containsKey(name)) {
            return true;
        }

        // values contains this key, we should check it out
        if (values != null && values.containsKey(name)) {
            Object value = values.get(name);
            if (value == null) {
                if (newValue == null) {
                    return false;
                }
            } else if (value.equals(newValue)) {
                return false;
            }
        }

        // otherwise, good to save
        return true;
    }

    /**
     * Sets the specified {@link Property} to the given value. For generated models, it is preferred to call a
     * generated set[Property] method instead.
     *
     * @param property the property to set
     * @param value the new value for the property
     */
    public <TYPE> void set(Property<TYPE> property, TYPE value) {
        setInternal(property, value, true);
    }

    /**
     * Sets the specified {@link Property} to the given value. This method allows ignoring table aliases when
     * determining which ValuesStorage to use, and compares only model classes. This method is not intended to be a
     * part of the public API and only exists to support {@link ViewModel#mapToModel(AbstractModel, SqlTable)}
     *
     * @param property the property to set
     * @param value the new value for the property
     */
    protected <TYPE> void setInternal(Property<TYPE> property, TYPE value, boolean matchTableName) {
        if (setValues == null) {
            setValues = newValuesStorage();
        }
        if (otherTableValues == null) {
            otherTableValues = newValuesStorage();
        }

        if (!shouldSaveValue(property, value)) {
            return;
        }

        ValuesStorage valuesStorage = setValues;
        ValuesStorageSavingVisitor valuesSaver = saver;
        boolean useOtherValuesStorage = matchTableName ?
                !TableModelName.equals(property.tableModelName, tableModelName) :
                !TableModelName.equalsClassOnly(property.tableModelName, tableModelName);
        if (useOtherValuesStorage) {
            valuesStorage = otherTableValues;
            valuesSaver = otherTableSaver;
        }
        valuesSaver.save(property, valuesStorage, value);
    }

    /**
     * Analogous to {@link #readPropertiesFromValuesStorage(ValuesStorage, Property[])} but adds the values to the
     * model as set values, i.e. marks the model as dirty with these values.
     *
     * @param values a {@link ValuesStorage} to read from
     * @param properties which properties to read from the values. Only properties specified in this list will be read
     */
    public void setPropertiesFromValuesStorage(ValuesStorage values, Property<?>... properties) {
        if (values != null) {
            if (setValues == null) {
                setValues = newValuesStorage();
            }
            if (otherTableValues == null) {
                otherTableValues = newValuesStorage();
            }
            for (Property<?> property : properties) {
                String key = property.getNameForModelStorage(tableModelName);
                ValuesStorage valuesStorage = TableModelName.equals(property.tableModelName, tableModelName) ?
                        setValues : otherTableValues;

                if (values.containsKey(key)) {
                    Object value = property.accept(valueCastingVisitor, values.get(key));
                    if (!TableModelName.equals(property.tableModelName, tableModelName) ||
                            shouldSaveValue(key, value)) {
                        valuesStorage.put(key, value, true);
                    }
                }
            }
        }
    }

    /**
     * Analogous to {@link #readPropertiesFromMap(Map, Property[])} but adds the values to the model as set values,
     * i.e. marks the model as dirty with these values.
     *
     * @param values a key-value pairing of values to read from
     * @param properties which properties to read from the values. Only properties specified in this list will be read
     */
    public void setPropertiesFromMap(Map<String, Object> values, Property<?>... properties) {
        if (values == null) {
            return;
        }
        setPropertiesFromValuesStorage(new MapValuesStorage(values), properties);
    }

    /**
     * Clear the value for the given {@link Property}
     *
     * @param property the property to clear
     */
    public void clearValue(Property<?> property) {
        if (TableModelName.equals(property.tableModelName, tableModelName)) {
            if (setValues != null && setValues.containsKey(property.getExpression())) {
                setValues.remove(property.getExpression());
            }

            if (values != null && values.containsKey(property.getExpression())) {
                values.remove(property.getExpression());
            }
        } else {
            if (otherTableValues != null && otherTableValues.containsKey(property.getSelectName())) {
                otherTableValues.remove(property.getSelectName());
            }
        }
    }

    // --- storing and retrieving transitory values

    /**
     * Add transitory data to the model. Transitory data is meant for developers to attach short-lived metadata to
     * models and is not persisted to the database.
     *
     * @param key the key for the transitory data
     * @param value the value for the transitory data
     * @see #getTransitory(String)
     */
    public void putTransitory(String key, Object value) {
        if (transitoryData == null) {
            transitoryData = new HashMap<>();
        }
        transitoryData.put(key, value);
    }

    /**
     * Get the transitory metadata object for the given key
     *
     * @param key the key for the transitory data
     * @return the transitory data if it exists, or null otherwise
     * @see #putTransitory(String, Object)
     */
    public Object getTransitory(String key) {
        if (transitoryData == null) {
            return null;
        }
        return transitoryData.get(key);
    }

    /**
     * Remove the transitory object for the specified key, if one exists
     *
     * @param key the key for the transitory data
     * @return the removed transitory value, or null if none existed
     * @see #putTransitory(String, Object)
     */
    public Object clearTransitory(String key) {
        if (transitoryData == null) {
            return null;
        }
        return transitoryData.remove(key);
    }

    /**
     * @return all transitory keys set on this model
     * @see #putTransitory(String, Object)
     */
    public Set<String> getAllTransitoryKeys() {
        if (transitoryData == null) {
            return null;
        }
        return transitoryData.keySet();
    }

    // --- convenience wrappers for using transitory data as flags

    /**
     * Convenience for using transitory data as a flag
     *
     * @param key the key for the transitory data
     * @return true if a transitory object is set for the given key, false otherwise
     */
    public boolean hasTransitory(String key) {
        return getTransitory(key) != null;
    }

    /**
     * Convenience for using transitory data as a flag. Removes the transitory data for this key if one existed.
     *
     * @param key the key for the transitory data
     * @return true if a transitory object is set for the given flag, false otherwise
     */
    public boolean checkAndClearTransitory(String key) {
        return clearTransitory(key) != null;
    }

    /**
     * Visitor that saves a value into a content values store
     */
    private static class ValuesStorageSavingVisitor implements PropertyWritingVisitor<Void, ValuesStorage, Object> {

        public void save(Property<?> property, ValuesStorage newStore, Object value) {
            if (value != null) {
                property.accept(this, newStore, value);
            } else {
                newStore.putNull(getStorageName(property));
            }
        }

        @Override
        public Void visitDouble(Property<Double> property, ValuesStorage dst, Object value) {
            dst.put(getStorageName(property), (Double) value);
            return null;
        }

        @Override
        public Void visitInteger(Property<Integer> property, ValuesStorage dst, Object value) {
            dst.put(getStorageName(property), (Integer) value);
            return null;
        }

        @Override
        public Void visitLong(Property<Long> property, ValuesStorage dst, Object value) {
            dst.put(getStorageName(property), (Long) value);
            return null;
        }

        @Override
        public Void visitString(Property<String> property, ValuesStorage dst, Object value) {
            dst.put(getStorageName(property), (String) value);
            return null;
        }

        @Override
        public Void visitBoolean(Property<Boolean> property, ValuesStorage dst, Object value) {
            if (value instanceof Boolean) {
                dst.put(getStorageName(property), (Boolean) value);
            } else if (value instanceof Integer) {
                dst.put(getStorageName(property), ((Integer) value) != 0);
            }
            return null;
        }

        @Override
        public Void visitBlob(Property<byte[]> property, ValuesStorage dst, Object value) {
            dst.put(getStorageName(property), (byte[]) value);
            return null;
        }

        protected String getStorageName(Property<?> property) {
            // If we got here, we already know we have a table match, so it's ok to fake the argument to this method
            return property.getNameForModelStorage(property.tableModelName);
        }
    }

    private static class OtherTableValuesStorageSavingVisitor extends ValuesStorageSavingVisitor {

        @Override
        protected String getStorageName(Property<?> property) {
            return property.getSelectName();
        }
    }

    private static class ValueCastingVisitor implements PropertyVisitor<Object, Object> {

        @Override
        public Object visitInteger(Property<Integer> property, Object data) {
            if (data == null || data instanceof Integer) {
                return data;
            } else if (data instanceof Number) {
                return ((Number) data).intValue();
            } else if (data instanceof Boolean) {
                return (Boolean) data ? 1 : 0;
            } else if (data instanceof String) {
                try {
                    return Integer.valueOf((String) data);
                } catch (NumberFormatException e) {
                    // Suppress and throw the class cast
                }
            }
            throw new ClassCastException("Value " + data + " could not be cast to Integer");
        }

        @Override
        public Object visitLong(Property<Long> property, Object data) {
            if (data == null || data instanceof Long) {
                return data;
            } else if (data instanceof Number) {
                return ((Number) data).longValue();
            } else if (data instanceof Boolean) {
                return (Boolean) data ? 1L : 0L;
            } else if (data instanceof String) {
                try {
                    return Long.valueOf((String) data);
                } catch (NumberFormatException e) {
                    // Suppress and throw the class cast
                }
            }
            throw new ClassCastException("Value " + data + " could not be cast to Long");
        }

        @Override
        public Object visitDouble(Property<Double> property, Object data) {
            if (data == null || data instanceof Double) {
                return data;
            } else if (data instanceof Number) {
                return ((Number) data).doubleValue();
            } else if (data instanceof String) {
                try {
                    return Double.valueOf((String) data);
                } catch (NumberFormatException e) {
                    // Suppress and throw the class cast
                }
            }
            throw new ClassCastException("Value " + data + " could not be cast to Double");
        }

        @Override
        public Object visitString(Property<String> property, Object data) {
            if (data == null || data instanceof String) {
                return data;
            } else {
                return String.valueOf(data);
            }
        }

        @Override
        public Object visitBoolean(Property<Boolean> property, Object data) {
            if (data == null || data instanceof Boolean) {
                return data;
            } else if (data instanceof Number) {
                return ((Number) data).intValue() != 0;
            }
            throw new ClassCastException("Value " + data + " could not be cast to Boolean");
        }

        @Override
        public Object visitBlob(Property<byte[]> property, Object data) {
            if (data != null && !(data instanceof byte[])) {
                throw new ClassCastException("Data " + data + " could not be cast to byte[]");
            }
            return data;
        }

    }
}

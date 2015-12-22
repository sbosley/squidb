/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the Apache 2.0 License.
 * See the accompanying LICENSE file for terms.
 */
package com.yahoo.squidb.sql;

import com.yahoo.squidb.utility.VersionCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SqlBuilder {

    public static final int FLAG_NEEDS_VALIDATION = 1;
    public static final int FLAG_TOP_LEVEL_SELECT = 1 << 1;

    private static final int STRING_BUILDER_INITIAL_CAPACITY = 128;

    public final StringBuilder sql = new StringBuilder(STRING_BUILDER_INITIAL_CAPACITY);
    public final VersionCode sqliteVersion;
    final List<Object> args;
    private int flags = 0;

    SqlBuilder(VersionCode sqliteVersion, boolean withBoundArguments) {
        this.sqliteVersion = sqliteVersion;
        this.args = withBoundArguments ? new ArrayList<>() : null;
    }

    /**
     * @return the compiled SQL string
     */
    public String getSqlString() {
        return sql.toString();
    }

    /**
     * @return a List of objects to bind as arguments to the SQL statement, or null if bound arguments were disabled
     */
    List<Object> getBoundArguments() {
        return args;
    }

    /**
     * @return true if the given flag is set
     */
    public boolean getFlag(int flag) {
        return (flags & flag) > 0;
    }

    /**
     * Set the given flag to true
     *
     * @param flag the flag to set
     */
    public void setFlag(int flag) {
        flags |= flag;
    }

    /**
     * Clears the given flag
     *
     * @param flag the flag to clear
     */
    public void clearFlag(int flag) {
        flags &= ~flag;
    }

    /**
     * Append a value to a SQL string being built. If the SqlBuilder has bound arguments enabled, a '?' may be placed
     * in the SQL string and the value added to the bound arguments list instead. This method properly handles
     * {@link Field}, {@link Property}, {@link Query}, and other database objects.
     * <p>
     * This method is intended for adding things like {@link Field} objects or values that serve as arguments to
     * {@link Criterion}s to the SQL string. String objects will be escaped and surrounded by quotes, or bound as an
     * argument to the SQL. If you want to append something directly to the SQL string (like a parentheses or a
     * syntactic string like SELECT), you can access the {@link #sql} StringBuilder directly.
     *
     * @param value The value to be appended
     * @param forSqlValidation forSqlValidation true if this statement is being compiled to validate against malicious
     * SQL
     */
    public void addValueToSql(Object value, boolean forSqlValidation) {
        if (value instanceof DBObject<?>) {
            ((DBObject<?>) value).appendQualifiedExpression(this, forSqlValidation);
        } else if (value instanceof Query) {
            Query query = (Query) value;
            sql.append("(");
            query.appendToSqlBuilder(this, forSqlValidation);
            sql.append(")");
        } else if (value instanceof CompilableWithArguments) {
            ((CompilableWithArguments) value).appendToSqlBuilder(this, forSqlValidation);
        } else if (value instanceof Collection<?>) {
            addCollectionArg((Collection<?>) value);
        } else if (args == null) {
            sql.append(SqlUtils.toSanitizedString(value));
        } else {
            if (value != null) {
                sql.append(SqlStatement.REPLACEABLE_PARAMETER);
                args.add(value);
            } else {
                sql.append("NULL");
            }
        }
    }

    void addCollectionArg(Collection<?> value) {
        if (value != null) {
            if (args == null) {
                SqlUtils.addInlineCollectionToSqlString(sql, value);
            } else {
                sql.append(SqlStatement.REPLACEABLE_ARRAY_PARAMETER);
                args.add(value);
            }
        }
    }

    void appendConcatenatedCompilables(List<? extends CompilableWithArguments> compilables, String separator,
            boolean forSqlValidation) {
        if (compilables != null && !compilables.isEmpty()) {
            boolean needSeparator = false;
            for (CompilableWithArguments compilable : compilables) {
                if (needSeparator) {
                    sql.append(separator);
                }
                needSeparator = true;
                compilable.appendToSqlBuilder(this, forSqlValidation);
            }
        }
    }

}

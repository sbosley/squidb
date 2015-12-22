/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the Apache 2.0 License.
 * See the accompanying LICENSE file for terms.
 */
package com.yahoo.squidb.sql;

import com.yahoo.squidb.utility.VersionCode;

abstract class CompilableWithArguments {

    @Override
    public String toString() {
        return toRawSql(VersionCode.LATEST);
    }

    public final String toRawSql(VersionCode sqliteVersion) {
        return toRawSql(sqliteVersion, defaultFlags());
    }

    public final String toRawSql(VersionCode sqliteVersion, int flags) {
        return buildSql(sqliteVersion, false, false, flags).getSqlString();
    }

    protected final SqlBuilder buildSql(VersionCode sqliteVersion, boolean withBoundArguments,
            boolean forSqlValidation, int flags) {
        SqlBuilder builder = new SqlBuilder(sqliteVersion, withBoundArguments);
        builder.setFlag(flags);
        appendToSqlBuilder(builder, forSqlValidation);
        return builder;
    }

    protected int defaultFlags() {
        return 0;
    }

    abstract void appendToSqlBuilder(SqlBuilder builder, boolean forSqlValidation);

}

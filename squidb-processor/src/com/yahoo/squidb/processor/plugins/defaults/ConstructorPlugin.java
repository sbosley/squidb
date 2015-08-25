/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the Apache 2.0 License.
 * See the accompanying LICENSE file for terms.
 */
package com.yahoo.squidb.processor.plugins.defaults;

import com.yahoo.aptutils.model.DeclaredTypeName;
import com.yahoo.aptutils.utils.AptUtils;
import com.yahoo.aptutils.writer.JavaFileWriter;
import com.yahoo.aptutils.writer.expressions.Expressions;
import com.yahoo.aptutils.writer.parameters.MethodDeclarationParameters;
import com.yahoo.squidb.processor.TypeConstants;
import com.yahoo.squidb.processor.plugins.Plugin;
import com.yahoo.squidb.processor.plugins.PluginWriter;
import com.yahoo.squidb.processor.writers.ModelFileWriter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

public class ConstructorPlugin extends Plugin {

    public ConstructorPlugin(AptUtils utils) {
        super(utils);
    }

    @Override
    public List<? extends PluginWriter> getWritersForElement(TypeElement modelSpecElement,
            DeclaredTypeName modelSpecName, DeclaredTypeName generatedModelName) {
        return Collections.singletonList(new ConstructorWriter(generatedModelName));
    }

    private class ConstructorWriter extends PluginWriter {

        private final DeclaredTypeName generatedClassName;

        private ConstructorWriter(DeclaredTypeName generatedClassName) {
            this.generatedClassName = generatedClassName;
        }

        @Override
        public void addRequiredImports(Set<DeclaredTypeName> imports) {
            imports.add(TypeConstants.SQUID_CURSOR);
            imports.add(TypeConstants.CONTENT_VALUES);
        }

        @Override
        public void writeConstructors(JavaFileWriter writer) throws IOException {
            MethodDeclarationParameters params = new MethodDeclarationParameters()
                    .setModifiers(Modifier.PUBLIC)
                    .setConstructorName(generatedClassName);
            writer.beginConstructorDeclaration(params)
                    .writeStringStatement("super()")
                    .finishMethodDefinition();

            DeclaredTypeName squidCursorType = TypeConstants.SQUID_CURSOR.clone();
            squidCursorType.setTypeArgs(Collections.singletonList(generatedClassName));
            params.setArgumentTypes(squidCursorType).setArgumentNames("cursor");
            writer.beginConstructorDeclaration(params)
                    .writeStringStatement("this()")
                    .writeStringStatement("readPropertiesFromCursor(cursor)")
                    .finishMethodDefinition();

            params.setArgumentTypes(Collections.singletonList(TypeConstants.CONTENT_VALUES))
                    .setArgumentNames("contentValues");
            writer.beginConstructorDeclaration(params)
                    .writeStatement(Expressions.callMethod("this", "contentValues",
                            ModelFileWriter.PROPERTIES_ARRAY_NAME))
                    .finishMethodDefinition();

            params.setArgumentTypes(Arrays.asList(TypeConstants.CONTENT_VALUES, TypeConstants.PROPERTY_VARARGS))
                    .setArgumentNames("contentValues", "withProperties");
            writer.beginConstructorDeclaration(params)
                    .writeStringStatement("this()")
                    .writeStringStatement("readPropertiesFromContentValues(contentValues, withProperties)")
                    .finishMethodDefinition();
        }
    }
}
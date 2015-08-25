/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the Apache 2.0 License.
 * See the accompanying LICENSE file for terms.
 */
package com.yahoo.squidb.processor.plugins.defaults;

import com.yahoo.aptutils.model.CoreTypes;
import com.yahoo.aptutils.model.DeclaredTypeName;
import com.yahoo.aptutils.utils.AptUtils;
import com.yahoo.aptutils.writer.JavaFileWriter;
import com.yahoo.aptutils.writer.expressions.Expression;
import com.yahoo.aptutils.writer.expressions.Expressions;
import com.yahoo.aptutils.writer.parameters.MethodDeclarationParameters;
import com.yahoo.squidb.annotations.ModelMethod;
import com.yahoo.squidb.processor.TypeConstants;
import com.yahoo.squidb.processor.plugins.Plugin;
import com.yahoo.squidb.processor.plugins.PluginWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

public class ModelMethodPlugin extends Plugin {

    public ModelMethodPlugin(AptUtils utils) {
        super(utils);
    }

    @Override
    public List<? extends PluginWriter> getWritersForElement(TypeElement modelSpecElement,
            final DeclaredTypeName modelSpecName, DeclaredTypeName generatedModelName) {
        final List<ExecutableElement> staticModelMethods = new ArrayList<ExecutableElement>();
        final List<ExecutableElement> modelMethods = new ArrayList<ExecutableElement>();
        List<? extends Element> enclosedElements = modelSpecElement.getEnclosedElements();
        for (Element e : enclosedElements) {
            if (e instanceof ExecutableElement) {
                checkExecutableElement((ExecutableElement) e, modelMethods, staticModelMethods, generatedModelName);
            }
        }
        if (!AptUtils.isEmpty(modelMethods) || !AptUtils.isEmpty(staticModelMethods)) {
            return Collections.singletonList(new ModelMethodWriter(modelMethods, staticModelMethods, modelSpecName));
        }
        return null;
    }

    private void checkExecutableElement(ExecutableElement e, List<ExecutableElement> modelMethods,
            List<ExecutableElement> staticModelMethods, DeclaredTypeName modelClass) {
        Set<Modifier> modifiers = e.getModifiers();
        if (e.getKind() == ElementKind.CONSTRUCTOR) {
            // Don't copy constructors
            return;
        }
        if (!modifiers.contains(Modifier.STATIC)) {
            utils.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Model spec objects should never be instantiated, so non-static methods are meaningless. " +
                            "Did you mean to make this a static method?", e);
            return;
        }
        ModelMethod methodAnnotation = e.getAnnotation(ModelMethod.class);
        // Private static methods may be unannotated if they are called by a public annotated method.
        // Don't assume error if method is private
        if (methodAnnotation == null) {
            if (modifiers.contains(Modifier.PUBLIC)) {
                staticModelMethods.add(e);
            } else if (!modifiers.contains(Modifier.PRIVATE)) {
                utils.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "This method will not be added to the model definition. " +
                                "Did you mean to annotate this method with @ModelMethod?", e);
            }
        } else {
            List<? extends VariableElement> params = e.getParameters();
            if (params.size() == 0) {
                utils.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@ModelMethod methods must have an abstract model as their first argument", e);
            } else {
                VariableElement firstParam = params.get(0);
                TypeMirror paramType = firstParam.asType();
                if (!checkFirstArgType(paramType, modelClass)) {
                    utils.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@ModelMethod methods must have an abstract model as their first argument", e);
                } else {
                    modelMethods.add(e);
                }
            }
        }
    }

    private boolean checkFirstArgType(TypeMirror type, DeclaredTypeName generatedClassName) {
        if (type instanceof ErrorType) {
            return true;
        }
        if (!(type instanceof DeclaredType)) {
            return false;
        }

        DeclaredTypeName typeName = (DeclaredTypeName) utils.getTypeNameFromTypeMirror(type);

        return typeName.equals(generatedClassName) || typeName.equals(TypeConstants.ABSTRACT_MODEL);
    }

    private class ModelMethodWriter extends PluginWriter {

        private final List<ExecutableElement> modelMethods;
        private final List<ExecutableElement> staticModelMethods;
        private final DeclaredTypeName modelSpecName;

        private ModelMethodWriter(List<ExecutableElement> modelMethods,
                List<ExecutableElement> staticModelMethods, DeclaredTypeName modelSpecName) {
            this.modelMethods = modelMethods;
            this.staticModelMethods = staticModelMethods;
            this.modelSpecName = modelSpecName;
        }

        @Override
        public void addRequiredImports(Set<DeclaredTypeName> imports) {
            utils.accumulateImportsFromElements(imports, modelMethods);
            utils.accumulateImportsFromElements(imports, staticModelMethods);
        }

        @Override
        public void writeMethods(JavaFileWriter writer) throws IOException {
            for (ExecutableElement e : modelMethods) {
                emitModelMethod(writer, e, Modifier.PUBLIC);
            }
            for (ExecutableElement e : staticModelMethods) {
                emitModelMethod(writer, e, Modifier.PUBLIC, Modifier.STATIC);
            }
        }

        private void emitModelMethod(JavaFileWriter writer, ExecutableElement e, Modifier... modifiers)
                throws IOException {
            MethodDeclarationParameters params = utils.methodDeclarationParamsFromExecutableElement(e, modifiers);

            ModelMethod methodAnnotation = e.getAnnotation(ModelMethod.class);
            List<Object> arguments = new ArrayList<Object>();
            if (methodAnnotation != null) {
                String name = methodAnnotation.name();
                if (!AptUtils.isEmpty(name)) {
                    params.setMethodName(name);
                }
                params.getArgumentTypes().remove(0);
                params.getArgumentNames().remove(0);
                arguments.add(0, "this");
            }
            arguments.addAll(params.getArgumentNames());
            Expression methodCall = Expressions.staticMethod(modelSpecName,
                    e.getSimpleName().toString(), arguments);
            if (!CoreTypes.VOID.equals(params.getReturnType())) {
                methodCall = methodCall.returnExpr();
            }
            writer.beginMethodDefinition(params)
                    .writeStatement(methodCall)
                    .finishMethodDefinition();
        }
    }
}
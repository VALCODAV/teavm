/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.javascript.rendering;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.teavm.ast.ClassNode;
import org.teavm.ast.MethodNode;
import org.teavm.ast.MethodNodePart;
import org.teavm.ast.VariableNode;
import org.teavm.backend.javascript.codegen.NamingException;
import org.teavm.backend.javascript.codegen.NamingOrderer;
import org.teavm.backend.javascript.codegen.NamingStrategy;
import org.teavm.backend.javascript.codegen.SourceWriter;
import org.teavm.backend.javascript.spi.Generator;
import org.teavm.backend.javascript.spi.GeneratorContext;
import org.teavm.backend.javascript.spi.RendererListener;
import org.teavm.common.ServiceRepository;
import org.teavm.debugging.information.DebugInformationEmitter;
import org.teavm.debugging.information.DummyDebugInformationEmitter;
import org.teavm.diagnostics.Diagnostics;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.FieldReference;
import org.teavm.model.ListableClassReaderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.vm.RenderingException;

public class Renderer implements RenderingManager {
    private final NamingStrategy naming;
    private final SourceWriter writer;
    private final ListableClassReaderSource classSource;
    private final ClassLoader classLoader;
    private boolean minifying;
    private final Properties properties = new Properties();
    private final ServiceRepository services;
    private DebugInformationEmitter debugEmitter = new DummyDebugInformationEmitter();
    private final Set<MethodReference> asyncMethods;
    private final Set<MethodReference> asyncFamilyMethods;
    private final Diagnostics diagnostics;
    private RenderingContext context;
    private List<PostponedFieldInitializer> postponedFieldInitializers = new ArrayList<>();
    private List<RendererListener> listeners = new ArrayList<>();
    private Map<MethodDescriptor, MethodNode> methodNodeMap = new HashMap<>();

    public Renderer(SourceWriter writer, Set<MethodReference> asyncMethods, Set<MethodReference> asyncFamilyMethods,
            Diagnostics diagnostics, RenderingContext context) {
        this.naming = context.getNaming();
        this.writer = writer;
        this.classSource = context.getClassSource();
        this.classLoader = context.getClassLoader();
        this.services = context.getServices();
        this.asyncMethods = new HashSet<>(asyncMethods);
        this.asyncFamilyMethods = new HashSet<>(asyncFamilyMethods);
        this.diagnostics = diagnostics;
        this.context = context;
    }

    public void addListener(RendererListener listener) {
        listeners.add(listener);
    }

    @Override
    public SourceWriter getWriter() {
        return writer;
    }

    @Override
    public NamingStrategy getNaming() {
        return naming;
    }

    @Override
    public boolean isMinifying() {
        return minifying;
    }

    public void setMinifying(boolean minifying) {
        this.minifying = minifying;
    }

    @Override
    public ListableClassReaderSource getClassSource() {
        return classSource;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public Properties getProperties() {
        Properties properties = new Properties();
        properties.putAll(this.properties);
        return properties;
    }

    public DebugInformationEmitter getDebugEmitter() {
        return debugEmitter;
    }

    public void setDebugEmitter(DebugInformationEmitter debugEmitter) {
        this.debugEmitter = debugEmitter;
    }

    public void setProperties(Properties properties) {
        this.properties.clear();
        this.properties.putAll(properties);
    }

    public void renderStringPool() throws RenderingException {
        if (context.getStringPool().isEmpty()) {
            return;
        }
        try {
            writer.append("$rt_stringPool([");
            for (int i = 0; i < context.getStringPool().size(); ++i) {
                if (i > 0) {
                    writer.append(',').ws();
                }
                writer.append('"').append(RenderingUtil.escapeString(context.getStringPool().get(i))).append('"');
            }
            writer.append("]);").newLine();
        } catch (IOException e) {
            throw new RenderingException("IO error", e);
        }
    }

    public void renderStringConstants() throws RenderingException {
        try {
            for (PostponedFieldInitializer initializer : postponedFieldInitializers) {
                writer.appendStaticField(initializer.field).ws().append("=").ws()
                        .append(context.constantToString(initializer.value)).append(";").softNewLine();
            }
        } catch (IOException e) {
            throw new RenderingException("IO error", e);
        }
    }

    public void renderRuntime() throws RenderingException {
        try {
            renderRuntimeCls();
            renderRuntimeString();
            renderRuntimeUnwrapString();
            renderRuntimeObjcls();
            renderRuntimeNullCheck();
            renderRuntimeIntern();
            renderRuntimeThreads();
        } catch (NamingException e) {
            throw new RenderingException("Error rendering runtime methods. See a cause for details", e);
        } catch (IOException e) {
            throw new RenderingException("IO error", e);
        }
    }

    private void renderRuntimeCls() throws IOException {
        writer.append("function $rt_cls(cls)").ws().append("{").softNewLine().indent();
        writer.append("return ").appendMethodBody("java.lang.Class", "getClass",
                ValueType.object("org.teavm.platform.PlatformClass"),
                ValueType.object("java.lang.Class")).append("(cls);")
                .softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeString() throws IOException {
        MethodReference stringCons = new MethodReference(String.class, "<init>", char[].class, void.class);
        writer.append("function $rt_str(str) {").indent().softNewLine();
        writer.append("if (str===null){").indent().softNewLine();
        writer.append("return null;").softNewLine();
        writer.outdent().append("}").softNewLine();
        writer.append("var characters = $rt_createCharArray(str.length);").softNewLine();
        writer.append("var charsBuffer = characters.data;").softNewLine();
        writer.append("for (var i = 0; i < str.length; i = (i + 1) | 0) {").indent().softNewLine();
        writer.append("charsBuffer[i] = str.charCodeAt(i) & 0xFFFF;").softNewLine();
        writer.outdent().append("}").softNewLine();
        writer.append("return ").append(naming.getNameForInit(stringCons)).append("(characters);").softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeUnwrapString() throws IOException {
        MethodReference stringLen = new MethodReference(String.class, "length", int.class);
        MethodReference getChars = new MethodReference(String.class, "getChars", int.class, int.class,
                char[].class, int.class, void.class);
        writer.append("function $rt_ustr(str) {").indent().softNewLine();
        writer.append("var result = \"\";").softNewLine();
        writer.append("var sz = ").appendMethodBody(stringLen).append("(str);").softNewLine();
        writer.append("var array = $rt_createCharArray(sz);").softNewLine();
        writer.appendMethodBody(getChars).append("(str, 0, sz, array, 0);").softNewLine();
        writer.append("for (var i = 0; i < sz; i = (i + 1) | 0) {").indent().softNewLine();
        writer.append("result += String.fromCharCode(array.data[i]);").softNewLine();
        writer.outdent().append("}").softNewLine();
        writer.append("return result;").softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeNullCheck() throws IOException {
        writer.append("function $rt_nullCheck(val) {").indent().softNewLine();
        writer.append("if (val === null) {").indent().softNewLine();
        writer.append("$rt_throw(").append(naming.getNameForInit(new MethodReference(NullPointerException.class,
                "<init>", void.class))).append("());").softNewLine();
        writer.outdent().append("}").softNewLine();
        writer.append("return val;").softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeIntern() throws IOException {
        writer.append("function $rt_intern(str) {").indent().softNewLine();
        writer.append("return ").appendMethodBody(new MethodReference(String.class, "intern", String.class))
            .append("(str);").softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeObjcls() throws IOException {
        writer.append("function $rt_objcls() { return ").appendClass("java.lang.Object").append("; }").newLine();
    }

    private void renderRuntimeThreads() throws IOException {
        writer.append("function $rt_getThread()").ws().append("{").indent().softNewLine();
        writer.append("return ").appendMethodBody(Thread.class, "currentThread", Thread.class).append("();")
                .softNewLine();
        writer.outdent().append("}").newLine();

        writer.append("function $rt_setThread(t)").ws().append("{").indent().softNewLine();
        writer.append("return ").appendMethodBody(Thread.class, "setCurrentThread", Thread.class, void.class)
                .append("(t);").softNewLine();
        writer.outdent().append("}").newLine();
    }

    private void renderRuntimeAliases() throws IOException {
        String[] names = { "$rt_throw", "$rt_compare", "$rt_nullCheck", "$rt_cls", "$rt_createArray",
                "$rt_isInstance", "$rt_nativeThread", "$rt_suspending", "$rt_resuming", "$rt_invalidPointer" };
        boolean first = true;
        for (String name : names) {
            if (!first) {
                writer.softNewLine();
            }
            first = false;
            writer.append("var ").appendFunction(name).ws().append('=').ws().append(name).append(";").softNewLine();
        }
        writer.newLine();
    }

    public void render(List<ClassNode> classes) throws RenderingException {
        if (minifying) {
            NamingOrderer orderer = new NamingOrderer();
            NameFrequencyEstimator estimator = new NameFrequencyEstimator(orderer, classSource, asyncMethods,
                    asyncFamilyMethods);
            for (ClassNode cls : classes) {
                estimator.estimate(cls);
            }
            orderer.apply(naming);
        }

        if (minifying) {
            try {
                renderRuntimeAliases();
            } catch (IOException e) {
                throw new RenderingException(e);
            }
        }
        for (ClassNode cls : classes) {
            renderDeclaration(cls);
            renderMethodBodies(cls);
        }
        renderClassMetadata(classes);
    }

    private void renderDeclaration(ClassNode cls) throws RenderingException {
        ClassHolder classHolder = cls.getClassHolder();
        debugEmitter.addClass(classHolder.getName(), classHolder.getParent());
        try {
            writer.append("function ").appendClass(classHolder.getName()).append("()").ws().append("{")
                    .indent().softNewLine();
            boolean thisAliased = false;
            List<FieldHolder> nonStaticFields = new ArrayList<>();
            List<FieldHolder> staticFields = new ArrayList<>();
            for (FieldHolder field : classHolder.getFields()) {
                if (field.getModifiers().contains(ElementModifier.STATIC)) {
                    staticFields.add(field);
                } else {
                    nonStaticFields.add(field);
                }
            }
            if (nonStaticFields.size() > 1) {
                thisAliased = true;
                writer.append("var a").ws().append("=").ws().append("this;").ws();
            }
            if (classHolder.getParent() != null) {
                writer.appendClass(classHolder.getParent()).append(".call(").append(thisAliased ? "a" : "this")
                        .append(");").softNewLine();
            }
            for (FieldHolder field : nonStaticFields) {
                Object value = field.getInitialValue();
                if (value == null) {
                    value = getDefaultValue(field.getType());
                }
                FieldReference fieldRef = new FieldReference(classHolder.getName(), field.getName());
                writer.append(thisAliased ? "a" : "this").append(".").appendField(fieldRef).ws()
                        .append("=").ws().append(context.constantToString(value)).append(";").softNewLine();
                debugEmitter.addField(field.getName(), naming.getNameFor(fieldRef));
            }

            if (classHolder.getName().equals("java.lang.Object")) {
                writer.append("this.$id").ws().append('=').ws().append("0;").softNewLine();
            }

            writer.outdent().append("}").newLine();

            for (FieldHolder field : staticFields) {
                Object value = field.getInitialValue();
                if (value == null) {
                    value = getDefaultValue(field.getType());
                }
                FieldReference fieldRef = new FieldReference(classHolder.getName(), field.getName());
                if (value instanceof String) {
                    context.constantToString(value);
                    postponedFieldInitializers.add(new PostponedFieldInitializer(fieldRef, (String) value));
                    value = null;
                }
                writer.append("var ").appendStaticField(fieldRef).ws().append("=").ws()
                        .append(context.constantToString(value)).append(";").softNewLine();
            }
        } catch (NamingException e) {
            throw new RenderingException("Error rendering class " + classHolder.getName()
                    + ". See cause for details", e);
        } catch (IOException e) {
            throw new RenderingException("IO error occurred", e);
        }
    }

    private void renderMethodBodies(ClassNode cls) throws RenderingException {
        ClassHolder classHolder = cls.getClassHolder();
        debugEmitter.emitClass(classHolder.getName());

        for (MethodNode methodNode : cls.getMethods()) {
            methodNodeMap.put(methodNode.getReference().getDescriptor(), methodNode);
        }

        try {
            List<MethodHolder> nonInitMethods = new ArrayList<>();
            MethodReader clinit = classSource.get(classHolder.getName()).getMethod(
                    new MethodDescriptor("<clinit>", ValueType.VOID));
            boolean needsClinit = clinit != null;
            List<MethodHolder> clinitMethods = new ArrayList<>();
            for (MethodHolder method : classHolder.getMethods()) {
                if (needsClinit && (method.getModifiers().contains(ElementModifier.STATIC)
                        || method.getReference().getName().equals("<init>"))) {
                    clinitMethods.add(method);
                } else {
                    nonInitMethods.add(method);
                }
            }

            if (needsClinit) {
                writer.append("function ").appendClass(classHolder.getName()).append("_$callClinit()").ws()
                        .append("{").softNewLine().indent();
                writer.appendClass(classHolder.getName()).append("_$callClinit").ws().append("=").ws()
                        .append("function(){};").newLine();
                for (MethodHolder method : clinitMethods) {
                    renderBody(method, true);
                }
                writer.appendMethodBody(new MethodReference(classHolder.getName(), clinit.getDescriptor()))
                        .append("();").softNewLine();
                writer.outdent().append("}").newLine();
            }

            if (!classHolder.hasModifier(ElementModifier.INTERFACE)) {
                for (MethodHolder method : classHolder.getMethods()) {
                    if (!method.getModifiers().contains(ElementModifier.STATIC)) {
                        if (method.getReference().getName().equals("<init>")) {
                            renderInitializer(method);
                        }
                    }
                }
            }

            for (MethodHolder method : nonInitMethods) {
                renderBody(method, false);
            }
        } catch (NamingException e) {
            throw new RenderingException("Error rendering class " + classHolder.getName()
                    + ". See a cause for details", e);
        } catch (IOException e) {
            throw new RenderingException("IO error occurred", e);
        }

        methodNodeMap.clear();
        debugEmitter.emitClass(null);
    }

    private void renderClassMetadata(List<ClassNode> classes) {
        try {
            Map<String, ClassNode> classMap = new HashMap<>();
            for (ClassNode cls : classes) {
                classMap.put(cls.getClassHolder().getName(), cls);
            }

            writer.append("$rt_metadata([");
            boolean first = true;
            for (String className : orderClasses()) {
                ClassNode cls = classMap.get(className);
                if (cls == null) {
                    continue;
                }

                if (!first) {
                    writer.append(',').softNewLine();
                }
                first = false;

                ClassHolder classHolder = cls.getClassHolder();
                writer.appendClass(classHolder.getName()).append(",").ws();
                writer.append("\"").append(RenderingUtil.escapeString(classHolder.getName())).append("\",").ws();
                if (classHolder.getParent() != null) {
                    writer.appendClass(classHolder.getParent());
                } else {
                    writer.append("0");
                }
                writer.append(',').ws();

                writer.append("[");
                boolean firstInterface = true;
                for (String iface : classHolder.getInterfaces()) {
                    if (!firstInterface) {
                        writer.append(",").ws();
                    }
                    firstInterface = false;
                    writer.appendClass(iface);
                }
                writer.append("],").ws();

                int flags = 0;
                if (classHolder.hasModifier(ElementModifier.ENUM)) {
                    flags |= 1;
                }
                writer.append(flags).append(',').ws();
                MethodReader clinit = classSource.get(classHolder.getName()).getMethod(
                        new MethodDescriptor("<clinit>", ValueType.VOID));
                if (clinit != null) {
                    writer.appendClass(classHolder.getName()).append("_$callClinit");
                } else {
                    writer.append('0');
                }
                writer.append(',').ws();

                List<String> stubNames = new ArrayList<>();
                List<MethodHolder> virtualMethods = new ArrayList<>();
                for (MethodHolder method : classHolder.getMethods()) {
                    if (clinit != null && (method.getModifiers().contains(ElementModifier.STATIC)
                            || method.getReference().getName().equals("<init>"))) {
                        stubNames.add(naming.getFullNameFor(method.getReference()));
                    }
                    if (!method.getModifiers().contains(ElementModifier.STATIC)) {
                        virtualMethods.add(method);
                    }
                }
                if (stubNames.size() == 1) {
                    writer.append("'").append(stubNames.get(0)).append("'");
                } else {
                    writer.append('[');
                    for (int j = 0; j < stubNames.size(); ++j) {
                        if (j > 0) {
                            writer.append(",").ws();
                        }
                        writer.append("'").append(stubNames.get(j)).append("'");
                    }
                    writer.append(']');
                }
                writer.append(',').ws();

                renderVirtualDeclarations(virtualMethods);
            }
            writer.append("]);").newLine();
        } catch (NamingException e) {
            throw new RenderingException("Error rendering class metadata. See a cause for details", e);
        } catch (IOException e) {
            throw new RenderingException("IO error occurred", e);
        }
    }

    private Collection<String> orderClasses() {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String className : classSource.getClassNames()) {
            orderClassesRec(className, visited, result);
        }
        return result;
    }

    private void orderClassesRec(String className, Set<String> visited, List<String> result) {
        if (!visited.add(className)) {
            return;
        }
        ClassReader cls = classSource.get(className);
        if (cls != null && cls.getParent() != null) {
            orderClassesRec(cls.getParent(), visited, result);
        }
        result.add(className);
    }

    private static Object getDefaultValue(ValueType type) {
        if (type instanceof ValueType.Primitive) {
            ValueType.Primitive primitive = (ValueType.Primitive) type;
            switch (primitive.getKind()) {
                case BOOLEAN:
                    return false;
                case BYTE:
                    return (byte) 0;
                case SHORT:
                    return (short) 0;
                case INTEGER:
                    return 0;
                case CHARACTER:
                    return '\0';
                case LONG:
                    return 0L;
                case FLOAT:
                    return 0F;
                case DOUBLE:
                    return 0.0;
            }
        }
        return null;
    }

    private void renderInitializer(MethodHolder method) throws IOException {
        MethodReference ref = method.getReference();
        debugEmitter.emitMethod(ref.getDescriptor());
        writer.append("function ").append(naming.getNameForInit(ref)).append("(");
        for (int i = 1; i <= ref.parameterCount(); ++i) {
            if (i > 1) {
                writer.append(",").ws();
            }
            writer.append(variableNameForInitializer(i));
        }
        writer.append(")").ws().append("{").softNewLine().indent();
        writer.append("var $r").ws().append("=").ws().append("new ").appendClass(
                ref.getClassName()).append("();").softNewLine();
        writer.append(naming.getFullNameFor(ref)).append("($r");
        for (int i = 1; i <= ref.parameterCount(); ++i) {
            writer.append(",").ws();
            writer.append(variableNameForInitializer(i));
        }
        writer.append(");").softNewLine();
        writer.append("return $r;").softNewLine();
        writer.outdent().append("}").newLine();
        debugEmitter.emitMethod(null);
    }

    private String variableNameForInitializer(int index) {
        return minifying ? RenderingUtil.indexToId(index) : "var_" + index;
    }

    private void renderVirtualDeclarations(List<MethodHolder> methods) throws NamingException, IOException {
        writer.append("[");
        boolean first = true;
        for (MethodHolder method : methods) {
            debugEmitter.emitMethod(method.getReference().getDescriptor());
            MethodReference ref = method.getReference();
            if (!first) {
                writer.append(",").ws();
            }
            first = false;
            emitVirtualDeclaration(ref);
            debugEmitter.emitMethod(null);
        }
        writer.append("]");
    }

    private void emitVirtualDeclaration(MethodReference ref) throws IOException {
        String methodName = naming.getNameFor(ref.getDescriptor());
        writer.append("\"").append(methodName).append("\"");
        writer.append(",").ws().append("function(");
        List<String> args = new ArrayList<>();
        for (int i = 1; i <= ref.parameterCount(); ++i) {
            args.add(variableNameForInitializer(i));
        }
        for (int i = 0; i < args.size(); ++i) {
            if (i > 0) {
                writer.append(",").ws();
            }
            writer.append(args.get(i));
        }
        writer.append(")").ws().append("{").ws();
        if (ref.getDescriptor().getResultType() != ValueType.VOID) {
            writer.append("return ");
        }
        writer.appendMethodBody(ref).append("(");
        writer.append("this");
        for (String arg : args) {
            writer.append(",").ws().append(arg);
        }
        writer.append(");").ws().append("}");
    }

    private void renderBody(MethodHolder methodHolder, boolean inner) throws IOException {
        if (context.getInjector(methodHolder.getReference()) != null) {
            return;
        }

        MethodNode method = methodNodeMap.get(methodHolder.getDescriptor());
        Generator generator = context.getGenerator(methodHolder);
        if (method == null && generator == null) {
            return;
        }

        StatementRenderer statementRenderer = new StatementRenderer(context, writer);
        statementRenderer.setCurrentMethod(method);

        MethodReference ref = methodHolder.getReference();
        debugEmitter.emitMethod(ref.getDescriptor());
        String name = naming.getFullNameFor(ref);
        if (inner) {
            writer.append(name).ws().append("=").ws().append("function(");
        } else {
            writer.append("function ").append(name).append("(");
        }
        int startParam = 0;
        if (methodHolder.getModifiers().contains(ElementModifier.STATIC)) {
            startParam = 1;
        }
        for (int i = startParam; i <= ref.parameterCount(); ++i) {
            if (i > startParam) {
                writer.append(",").ws();
            }
            writer.append(statementRenderer.variableName(i));
        }
        writer.append(")").ws().append("{").softNewLine().indent();

        if (generator != null) {
            generator.generate(new MethodBodyRenderer(statementRenderer), writer, methodHolder.getReference());
        } else {
            new MethodBodyRenderer(statementRenderer).render(method);
        }

        writer.outdent().append("}");
        if (inner) {
            writer.append(';');
        }
        writer.newLine();
        debugEmitter.emitMethod(null);
    }

    private class MethodBodyRenderer implements GeneratorContext {
        private boolean async;
        private StatementRenderer statementRenderer;

        public MethodBodyRenderer(StatementRenderer statementRenderer) {
            this.statementRenderer = statementRenderer;
        }

        public void render(MethodNode methodNode) {
            try {
                this.async = methodNode.getBody().size() > 1;
                statementRenderer.setAsync(async);
                MethodReference ref = methodNode.getReference();
                for (int i = 0; i < methodNode.getVariables().size(); ++i) {
                    debugEmitter.emitVariable(new String[] { methodNode.getVariables().get(i).getName() },
                            statementRenderer.variableName(i));
                }
                int variableCount = 0;
                for (VariableNode var : methodNode.getVariables()) {
                    variableCount = Math.max(variableCount, var.getIndex() + 1);
                }
                List<String> variableNames = new ArrayList<>();
                for (int i = ref.parameterCount() + 1; i < variableCount; ++i) {
                    variableNames.add(statementRenderer.variableName(i));
                }
                TryCatchFinder tryCatchFinder = new TryCatchFinder();
                for (MethodNodePart part : methodNode.getBody()) {
                    if (!tryCatchFinder.tryCatchFound) {
                        part.getStatement().acceptVisitor(tryCatchFinder);
                    }
                }
                boolean hasTryCatch = tryCatchFinder.tryCatchFound;
                if (hasTryCatch) {
                    variableNames.add("$je");
                }
                variableNames.add(context.pointerName());
                variableNames.add(context.tempVarName());
                if (!variableNames.isEmpty()) {
                    writer.append("var ");
                    for (int i = 0; i < variableNames.size(); ++i) {
                        if (i > 0) {
                            writer.append(",").ws();
                        }
                        writer.append(variableNames.get(i));
                    }
                    writer.append(";").softNewLine();
                }

                int firstToSave = 0;
                if (methodNode.getModifiers().contains(ElementModifier.STATIC)) {
                    firstToSave = 1;
                }

                String popName = minifying ? "l" : "pop";
                String pushName = minifying ? "s" : "push";

                if (async) {
                    writer.append(context.pointerName()).ws().append('=').ws().append("0;").softNewLine();
                    writer.append("if").ws().append("(").appendFunction("$rt_resuming").append("())").ws()
                            .append("{").indent().softNewLine();
                    writer.append("var ").append(context.threadName()).ws().append('=').ws()
                            .appendFunction("$rt_nativeThread").append("();").softNewLine();
                    writer.append(context.pointerName()).ws().append('=').ws().append(context.threadName()).append(".")
                            .append(popName).append("();");
                    for (int i = variableCount - 1; i >= firstToSave; --i) {
                        writer.append(statementRenderer.variableName(i)).ws().append('=').ws()
                                .append(context.threadName())
                                .append(".").append(popName).append("();");
                    }
                    writer.softNewLine();
                    writer.outdent().append("}").softNewLine();

                    if (methodNode.getModifiers().contains(ElementModifier.SYNCHRONIZED)) {
                        writer.append("try").ws().append('{').indent().softNewLine();
                    }
                    writer.append(context.mainLoopName()).append(":").ws().append("while").ws().append("(true)")
                            .ws().append("{").ws();
                    writer.append("switch").ws().append("(").append(context.pointerName()).append(")").ws()
                            .append('{').softNewLine();
                }

                for (int i = 0; i < methodNode.getBody().size(); ++i) {
                    if (async) {
                        writer.append("case ").append(i).append(":").indent().softNewLine();
                        if (i == 0 && methodNode.getModifiers().contains(ElementModifier.SYNCHRONIZED)) {
                            writer.appendMethodBody(new MethodReference(Object.class, "monitorEnter",
                                    Object.class, void.class));
                            writer.append("(");
                            appendMonitor(statementRenderer, methodNode);
                            writer.append(");").softNewLine();
                            statementRenderer.emitSuspendChecker();
                        }
                    }
                    MethodNodePart part = methodNode.getBody().get(i);
                    statementRenderer.setEnd(true);
                    statementRenderer.setCurrentPart(i);
                    part.getStatement().acceptVisitor(statementRenderer);
                    if (async) {
                        writer.outdent();
                    }
                }
                if (async) {
                    writer.append("default:").ws().appendFunction("$rt_invalidPointer").append("();").softNewLine();
                    writer.append("}}").softNewLine();
                }

                if (async) {
                    if (methodNode.getModifiers().contains(ElementModifier.SYNCHRONIZED)) {
                        writer.outdent().append("}").ws().append("finally").ws().append('{').indent().softNewLine();
                        writer.append("if").ws().append("(!").appendFunction("$rt_suspending").append("())")
                                .ws().append("{").indent().softNewLine();
                        writer.appendMethodBody(new MethodReference(Object.class, "monitorExit",
                                Object.class, void.class));
                        writer.append("(");
                        appendMonitor(statementRenderer, methodNode);
                        writer.append(");").softNewLine();
                        writer.outdent().append('}').softNewLine();
                        writer.outdent().append('}').softNewLine();
                    }

                    writer.appendFunction("$rt_nativeThread").append("().").append(pushName).append("(");
                    for (int i = firstToSave; i < variableCount; ++i) {
                        writer.append(statementRenderer.variableName(i)).append(',').ws();
                    }
                    writer.append(context.pointerName()).append(");");
                    writer.softNewLine();
                }
            } catch (IOException e) {
                throw new RenderingException("IO error occurred", e);
            }
        }

        @Override
        public String getParameterName(int index) {
            return statementRenderer.variableName(index);
        }

        @Override
        public ListableClassReaderSource getClassSource() {
            return classSource;
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        @Override
        public Properties getProperties() {
            return new Properties(properties);
        }

        @Override
        public <T> T getService(Class<T> type) {
            return services.getService(type);
        }

        @Override
        public boolean isAsync() {
            return async;
        }

        @Override
        public boolean isAsync(MethodReference method) {
            return asyncMethods.contains(method);
        }

        @Override
        public boolean isAsyncFamily(MethodReference method) {
            return asyncFamilyMethods.contains(method);
        }

        @Override
        public Diagnostics getDiagnostics() {
            return diagnostics;
        }

        @Override
        public String typeToClassString(ValueType type) {
            return context.typeToClsString(type);
        }
    }

    private void appendMonitor(StatementRenderer statementRenderer, MethodNode methodNode) throws IOException {
        if (methodNode.getModifiers().contains(ElementModifier.STATIC)) {
            writer.appendFunction("$rt_cls").append("(")
                    .appendClass(methodNode.getReference().getClassName()).append(")");
        } else {
            writer.append(statementRenderer.variableName(0));
        }
    }

    @Override
    public <T> T getService(Class<T> type) {
        return services.getService(type);
    }

    private static class PostponedFieldInitializer {
        FieldReference field;
        String value;

        public PostponedFieldInitializer(FieldReference field, String value) {
            this.field = field;
            this.value = value;
        }
    }
}
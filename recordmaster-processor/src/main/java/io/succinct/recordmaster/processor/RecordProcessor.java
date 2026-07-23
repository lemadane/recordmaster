package io.succinct.recordmaster.processor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.util.Set;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class RecordProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element elem : roundEnv.getRootElements()) {
            if (elem.getKind() == ElementKind.RECORD) {
                TypeElement typeElement = (TypeElement) elem;
                
                boolean isRecordMaster = false;
                // Check if it implements io.succinct.recordmaster.Record
                for (TypeMirror interfaceType : typeElement.getInterfaces()) {
                    if (interfaceType.toString().equals("io.succinct.recordmaster.Record")) {
                        isRecordMaster = true;
                        break;
                    }
                }
                
                // Check if annotated with @Table
                if (typeElement.getAnnotation(io.succinct.recordmaster.annotations.Table.class) != null) {
                    isRecordMaster = true;
                }

                if (isRecordMaster) {
                    generateFieldsClass(typeElement);
                }
            }
        }
        return false;
    }

    private void generateFieldsClass(TypeElement typeElement) {
        String qualifiedName = typeElement.getQualifiedName().toString();
        String packageName = "";
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = qualifiedName.substring(0, lastDot);
        }
        String simpleName = typeElement.getSimpleName().toString();
        String generatedClassName = simpleName + "Fields";

        try {
            JavaFileObject fileObject = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);
            try (Writer writer = fileObject.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("import io.succinct.recordmaster.Field;\n\n");
                writer.write("public final class " + generatedClassName + " {\n");
                writer.write("    private " + generatedClassName + "() {}\n\n");

                for (Element enclosed : typeElement.getEnclosedElements()) {
                    if (enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                        String fieldName = enclosed.getSimpleName().toString();
                        String typeStr = enclosed.asType().toString();
                        String boxedType = boxType(typeStr);

                        writer.write("    public static final Field<" + simpleName + ", " + boxedType + "> " + fieldName + " =\n");
                        writer.write("        new Field<>(\"" + fieldName + "\", " + simpleName + "::" + fieldName + ");\n\n");
                    }
                }

                writer.write("}\n");
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate fields class: " + e.getMessage());
        }
    }

    private String boxType(String typeStr) {
        return switch (typeStr) {
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "boolean" -> "java.lang.Boolean";
            case "char" -> "java.lang.Character";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            default -> typeStr;
        };
    }
}

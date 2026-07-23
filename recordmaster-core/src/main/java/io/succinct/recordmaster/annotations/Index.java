package io.succinct.recordmaster.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Repeatable(Indexes.class)
public @interface Index {
    String name() default "";
    boolean unique() default false;
    boolean ordered() default false;
}

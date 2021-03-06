package org.briarproject.api.db;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.google.inject.BindingAnnotation;

/**
 * Annotation for injecting the executor for database tasks.
 * <p>
 * The contract of this executor is that tasks are executed in the order
 * they're submitted, tasks are not executed concurrently, and submitting a
 * task will never block.
 */
@BindingAnnotation
@Target({ FIELD, METHOD, PARAMETER })
@Retention(RUNTIME)
public @interface DatabaseExecutor {}

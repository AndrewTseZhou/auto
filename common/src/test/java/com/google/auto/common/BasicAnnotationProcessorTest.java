/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.common;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.JavaSourcesSubjectFactory.javaSources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.testing.compile.JavaFileObjects;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;

@RunWith(JUnit4.class)
public class BasicAnnotationProcessorTest {

  private Set<Element> elementsGeneratingCode = Sets.newHashSet();

  @Retention(RetentionPolicy.SOURCE)
  public @interface RequiresGeneratedCode {}

  /** Asserts that the code generated by {@link GeneratesCode} and its processor is present.  */
  public class RequiresGeneratedCodeProcessor extends BasicAnnotationProcessor {
    boolean processed = false;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    protected Iterable<? extends ProcessingStep> initSteps() {
      return ImmutableSet.of(new ProcessingStep() {
        @Override
        public void process(
            SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
          processed = true;
          assertThat(elementsGeneratingCode).isNotEmpty();
        }

        @Override
        public Set<? extends Class<? extends Annotation>> annotations() {
          return ImmutableSet.of(RequiresGeneratedCode.class);
        }
      });
    }
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface GeneratesCode {}

  /** Generates a class called {@code test.SomeGeneratedClass}. */
  public class GeneratesCodeProcessor extends BasicAnnotationProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    protected Iterable<? extends ProcessingStep> initSteps() {
      return ImmutableSet.of(new ProcessingStep() {
        @Override
        public void process(
            SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {
          for (Element element : elementsByAnnotation.values()) {
            try {
              generateClass(element);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }

        @Override
        public Set<? extends Class<? extends Annotation>> annotations() {
          return ImmutableSet.of(GeneratesCode.class);
        }
      });
    }

    // TODO(gak): Use jimfs to simulate the file system.
    private void generateClass(Element sourceType) throws IOException {
      elementsGeneratingCode.add(sourceType);
      JavaFileObject source =
          processingEnv.getFiler().createSourceFile("test.SomeGeneratedClass", sourceType);
      PrintWriter writer = new PrintWriter(source.openWriter());
      writer.println("package test;");
      writer.println("public class SomeGeneratedClass {}");
      writer.close();
    }
  }

  @Test public void properlyDefersProcessing_typeElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "public class ClassA {",
        "  SomeGeneratedClass sgc;",
        "}");
    JavaFileObject classBFileObject = JavaFileObjects.forSourceLines("test.ClassB",
        "package test;",
        "",
        "@" + GeneratesCode.class.getCanonicalName(),
        "public class ClassB {}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, classBFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError();
    assertThat(requiresGeneratedCodeProcessor.processed).isTrue();
  }

  @Retention(RetentionPolicy.SOURCE)
  public @interface ReferencesAClass {
    Class<?> value();
  }

  @Test public void properlyDefersProcessing_packageElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + GeneratesCode.class.getCanonicalName(),
        "public class ClassA {",
        "}");
    JavaFileObject packageFileObject = JavaFileObjects.forSourceLines("test.package-info",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "@" + ReferencesAClass.class.getCanonicalName() + "(SomeGeneratedClass.class)",
        "package test;");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, packageFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError();
    assertThat(requiresGeneratedCodeProcessor.processed).isTrue();
  }

  @Test public void properlyDefersProcessing_argumentElement() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "public class ClassA {",
        "  SomeGeneratedClass sgc;",
        "  public void myMethod(@" + RequiresGeneratedCode.class.getCanonicalName() + " int myInt)",
        "  {}",
        "}");
    JavaFileObject classBFileObject = JavaFileObjects.forSourceLines("test.ClassB",
        "package test;",
        "",
        "public class ClassB {",
        "  public void myMethod(@" + GeneratesCode.class.getCanonicalName() + " int myInt) {}",
        "}");
    RequiresGeneratedCodeProcessor requiresGeneratedCodeProcessor =
        new RequiresGeneratedCodeProcessor();
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject, classBFileObject))
        .processedWith(requiresGeneratedCodeProcessor, new GeneratesCodeProcessor())
        .compilesWithoutError();
    assertThat(requiresGeneratedCodeProcessor.processed).isTrue();
  }

  @Test public void reportsMissingType() {
    JavaFileObject classAFileObject = JavaFileObjects.forSourceLines("test.ClassA",
        "package test;",
        "",
        "@" + RequiresGeneratedCode.class.getCanonicalName(),
        "public class ClassA {",
        "  SomeGeneratedClass bar;",
        "}");
    assertAbout(javaSources())
        .that(ImmutableList.of(classAFileObject))
        .processedWith(new RequiresGeneratedCodeProcessor())
        .failsToCompile()
        .withErrorContaining(RequiresGeneratedCodeProcessor.class.getCanonicalName())
        .in(classAFileObject).onLine(4);
  }
}

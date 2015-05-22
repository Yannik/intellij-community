/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.theoryinpractice.testng.configuration;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import junit.framework.Assert;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.testng.IDEATestNGRemoteListener;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlInclude;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestNGTreeHierarchyTest {
 
  @Test
  public void testOneTestMethod() throws Exception {
    final XmlSuite suite = new XmlSuite();
    final XmlTest test = new XmlTest();
    final XmlClass xmlClass = new XmlClass("a.ATest", false);
    xmlClass.getIncludedMethods().add(new XmlInclude("test1"));
    test.getClasses().add(xmlClass);
    suite.getTests().add(test);
    
    doTest(suite, "\n" +
                  "##teamcity[testSuiteStarted name ='ATest' locationHint = 'java:suite://a.ATest']\n" +
                  "\n" +
                  "##teamcity[testStarted name='test1' locationHint='java:test://a.ATest.test1|[0|]']\n" +
                  "\n" +
                  "##teamcity[testFinished name='test1']\n");
  }

  @Test
  public void testOneTestMethodWithMultipleInvocationCount() throws Exception {
    final XmlSuite suite = new XmlSuite();
    final XmlTest test = new XmlTest();
    final XmlClass xmlClass = new XmlClass("a.ATest", false);
    xmlClass.getIncludedMethods().add(new XmlInclude("test1", Arrays.asList(0, 1, 2), 0));
    test.getClasses().add(xmlClass);
    suite.getTests().add(test);

    doTest(suite, "\n" +
                  "##teamcity[testSuiteStarted name ='ATest' locationHint = 'java:suite://a.ATest']\n" +
                  "\n" +
                  "##teamcity[testStarted name='test1' locationHint='java:test://a.ATest.test1|[0|]']\n" +
                  "\n" +
                  "##teamcity[testFinished name='test1']\n" +
                  "\n" +
                  "##teamcity[testStarted name='test1 (1)' locationHint='java:test://a.ATest.test1|[1|]']\n" +
                  "\n" +
                  "##teamcity[testFinished name='test1 (1)']\n" +
                  "\n" +
                  "##teamcity[testStarted name='test1 (2)' locationHint='java:test://a.ATest.test1|[2|]']\n" +
                  "\n" +
                  "##teamcity[testFinished name='test1 (2)']\n");
  }

  @Test
  public void testConfigurationMethods() throws Exception {
    final StringBuffer buf = new StringBuffer();
    final IDEATestNGRemoteListener listener = createListener(buf);
    final String className = "a.ATest";
    listener.onSuiteStart(className, true);
    for(String methodName : new String[] {"test1", "test2"}) {
      listener.onConfigurationSuccess(Collections.singletonList(className), "setUp", 0);
      listener.onTestStart(Collections.singletonList(className), methodName, null, -1);
      listener.onTestFinished(methodName, 0);
      listener.onConfigurationSuccess(Collections.singletonList(className), "tearDown", 0);
    }
    listener.onSuiteFinish(className);

    Assert.assertEquals("output: " + buf, "\n" +
                                          "##teamcity[testSuiteStarted name ='ATest' locationHint = 'java:suite://a.ATest']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='setUp' locationHint='java:test://a.ATest.setUp']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='setUp']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='test1' locationHint='java:test://a.ATest.test1']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='test1']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='tearDown' locationHint='java:test://a.ATest.tearDown']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='tearDown']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='setUp' locationHint='java:test://a.ATest.setUp']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='setUp']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='test2' locationHint='java:test://a.ATest.test2']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='test2']\n" +
                                          "\n" +
                                          "##teamcity[testStarted name='tearDown' locationHint='java:test://a.ATest.tearDown']\n" +
                                          "\n" +
                                          "##teamcity[testFinished name='tearDown']\n" +
                                          "##teamcity[testSuiteFinished name='a.ATest']\n", StringUtil.convertLineSeparators(buf.toString()));
  }

  private static void doTest(XmlSuite suite, String expected) {
    final StringBuffer buf = new StringBuffer();
    final IDEATestNGRemoteListener listener = createListener(buf);

    for (XmlTest test : suite.getTests()) {
      for (XmlClass aClass : test.getClasses()) {
        final String classFQName = aClass.getName();
        for (XmlInclude include : aClass.getIncludedMethods()) {
          final String methodName = include.getName();
          List<Integer> numbers = include.getInvocationNumbers();
          if (numbers.isEmpty()) {
            numbers = Collections.singletonList(0);
          }
          for (Integer integer : numbers) {
            final String paramsString = IDEATestNGRemoteListener.getParamsString(ArrayUtil.EMPTY_OBJECT_ARRAY, integer);
            listener.onTestStart(Collections.singletonList(classFQName), methodName, paramsString, integer);
            listener.onTestFinished(methodName + (paramsString != null ? paramsString : ""), 0);
          }
        }
      }
    }

    Assert.assertEquals("output: " + buf, expected, StringUtil.convertLineSeparators(buf.toString()));
  }

  @NotNull
  private static IDEATestNGRemoteListener createListener(final StringBuffer buf) {
    return new IDEATestNGRemoteListener(new PrintStream(new OutputStream() {
        @Override
        public void write(int b) throws IOException {
          buf.append(new String(new byte[]{(byte)b}));
        }
      }));
  }
}

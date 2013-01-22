package com.jetbrains.python.codeInsight.testIntegration;

import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testIntegration.TestFinder;
import com.intellij.testIntegration.TestFinderHelper;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyDocStringOwner;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex;
import com.jetbrains.python.testing.PythonUnitTestUtil;
import com.jetbrains.python.testing.doctest.PythonDocTestUtil;
import com.jetbrains.python.testing.pytest.PyTestUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * User : catherine
 */
public class PyTestFinder implements TestFinder {
  public PyDocStringOwner findSourceElement(@NotNull PsiElement element) {
    return PsiTreeUtil.getParentOfType(element, PyClass.class, PyFunction.class);
  }

  @NotNull
  @Override
  public Collection<PsiElement> findTestsForClass(@NotNull PsiElement element) {
    PyDocStringOwner source = findSourceElement(element);
    if (source == null) return Collections.emptySet();

    String klassName = source.getName();
    Pattern pattern = Pattern.compile(".*" + klassName + ".*");

    List<Pair<? extends PsiNamedElement, Integer>> classesWithProximities = new ArrayList<Pair<? extends PsiNamedElement, Integer>>();

    if (source instanceof PyClass) {
      Collection<String> names = PyClassNameIndex.allKeys(element.getProject());
      for (String eachName : names) {
        if (pattern.matcher(eachName).matches()) {
          for (PyClass eachClass : PyClassNameIndex.find(eachName, element.getProject(), GlobalSearchScope.projectScope(element.getProject()))) {
            if (PythonUnitTestUtil.isTestCaseClass(eachClass) || PythonDocTestUtil.isDocTestClass(eachClass)) {
              classesWithProximities.add(
                  new Pair<PsiNamedElement, Integer>(eachClass, TestFinderHelper.calcTestNameProximity(klassName, eachName)));
            }
          }
        }
      }
    }
    else {
      Collection<String> names = PyFunctionNameIndex.allKeys(element.getProject());
      for (String eachName : names) {
        if (pattern.matcher(eachName).matches()) {
          for (PyFunction eachFunction : PyFunctionNameIndex.find(eachName, element.getProject(), GlobalSearchScope.projectScope(element.getProject()))) {
            if (PythonUnitTestUtil.isTestCaseFunction(
              eachFunction) || PythonDocTestUtil.isDocTestFunction(eachFunction)) {
              classesWithProximities.add(
                new Pair<PsiNamedElement, Integer>(eachFunction, TestFinderHelper.calcTestNameProximity(klassName, eachName)));
            }
          }
        }
      }
    }
    return TestFinderHelper.getSortedElements(classesWithProximities, true);
  }

  @NotNull
  @Override
  public Collection<PsiElement> findClassesForTest(@NotNull PsiElement element) {
    final PyFunction sourceFunction = PsiTreeUtil.getParentOfType(element, PyFunction.class);
    final PyClass source = PsiTreeUtil.getParentOfType(element, PyClass.class);
    if (sourceFunction == null && source == null) return Collections.emptySet();

    List<Pair<? extends PsiNamedElement, Integer>> classesWithWeights = new ArrayList<Pair<? extends PsiNamedElement, Integer>>();
    final List<Pair<String, Integer>> possibleNames = new ArrayList<Pair<String, Integer>>();
    if (source != null)
      possibleNames.addAll(TestFinderHelper.collectPossibleClassNamesWithWeights(source.getName()));
    if (sourceFunction != null)
      possibleNames.addAll(TestFinderHelper.collectPossibleClassNamesWithWeights(sourceFunction.getName()));

    for (Pair<String, Integer> eachNameWithWeight : possibleNames) {
      for (PyClass eachClass : PyClassNameIndex.find(eachNameWithWeight.first, element.getProject(),
                                                     GlobalSearchScope.projectScope(element.getProject()))) {
        if (!PyTestUtil.isPyTestClass(eachClass))
          classesWithWeights.add(new Pair<PsiNamedElement, Integer>(eachClass, eachNameWithWeight.second));
      }
      for (PyFunction function : PyFunctionNameIndex.find(eachNameWithWeight.first, element.getProject(),
                                                           GlobalSearchScope.projectScope(element.getProject()))) {
        if (!PyTestUtil.isPyTestFunction(function))
          classesWithWeights.add(new Pair<PsiNamedElement, Integer>(function, eachNameWithWeight.second));
      }

    }
    return TestFinderHelper.getSortedElements(classesWithWeights, false);
  }

  @Override
  public boolean isTest(@NotNull PsiElement element) {
    PyClass cl = PsiTreeUtil.getParentOfType(element, PyClass.class, false);
    if (cl != null)
      return PyTestUtil.isPyTestClass(cl);
    return false;
  }
}

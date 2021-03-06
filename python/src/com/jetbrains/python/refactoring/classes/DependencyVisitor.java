package com.jetbrains.python.refactoring.classes;

import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Searches for element in another element's usages.
 * Parametrize it with needle and make stack to accept it.
 *
 * @author Ilya.Kazakevich
 */
class DependencyVisitor extends PyRecursiveElementVisitor {

  @NotNull
  private final PyElement myElementToFind;
  private boolean myDependencyFound;

  /**
   * @param elementToFind what to find
   */
  DependencyVisitor(@NotNull final PyElement elementToFind) {
    myElementToFind = elementToFind;
  }

  @Override
  public void visitPyCallExpression(@NotNull final PyCallExpression node) {
    final PyExpression callee = node.getCallee();
    if (callee != null) {
      final PsiReference calleeReference = callee.getReference();
      if ((calleeReference != null) && calleeReference.isReferenceTo(myElementToFind)) {
        myDependencyFound = true;
        return;
      }
      final String calleeName = callee.getName();

      if ((calleeName != null) && calleeName.equals(myElementToFind.getName())) {  // Check by name also
        myDependencyFound = true;
      }
    }
  }

  @Override
  public void visitPyReferenceExpression(final PyReferenceExpression node) {

    final PsiPolyVariantReference reference = node.getReference();
    if (reference.isReferenceTo(myElementToFind)) {
      myDependencyFound = true;
      return;
    }
    // TODO: This step is member-type specific. Move to MemberManagers?
    if (myElementToFind instanceof PyAssignmentStatement) {
      final PyExpression[] targets = ((PyAssignmentStatement)myElementToFind).getTargets();

      if (targets.length != 1) {
        return;
      }
      final PyExpression expression = targets[0];

      if (reference.isReferenceTo(expression)) {
        myDependencyFound = true;
        return;
      }
      if (node.getText().equals(expression.getText())) { // Check by name also
        myDependencyFound = true;
      }
      return;
    }
    final PsiElement declaration = reference.resolve();
    myDependencyFound = PsiTreeUtil.findFirstParent(declaration, new PsiElementCondition()) != null;
  }

  public boolean isDependencyFound() {
    return myDependencyFound;
  }

  private class PsiElementCondition implements Condition<PsiElement> {
    @Override
    public boolean value(final PsiElement psiElement) {
      return psiElement.equals(myElementToFind);
    }
  }
}

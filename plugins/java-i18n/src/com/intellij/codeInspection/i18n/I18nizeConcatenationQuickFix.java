// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.i18n;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.lang.properties.psi.I18nizedTextGenerator;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiConcatenationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class I18nizeConcatenationQuickFix extends I18nizeQuickFix{
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.i18n.I18nizeConcatenationQuickFix");
  @NonNls private static final String PARAMETERS_OPTION_KEY = "PARAMETERS";

  @Override
  public void checkApplicability(final PsiFile psiFile, final Editor editor) throws IncorrectOperationException {
    PsiPolyadicExpression concatenation = getEnclosingLiteralConcatenation(psiFile, editor);
    if (concatenation != null) return;
    String message = CodeInsightBundle.message("quickfix.i18n.concatentation.error");
    throw new IncorrectOperationException(message);
  }

  @Override
  public JavaI18nizeQuickFixDialog createDialog(Project project, Editor editor, PsiFile psiFile) {
    PsiPolyadicExpression concatenation = getEnclosingLiteralConcatenation(psiFile, editor);
    assert concatenation != null;
    PsiLiteralExpression literalExpression = getContainingLiteral(concatenation);
    if (literalExpression == null) return null;
    return createDialog(project, psiFile, literalExpression);
  }

  @Override
  @NotNull
  public String getFamilyName() {
    return CodeInsightBundle.message("quickfix.i18n.concatentation");
  }

  @Override
  protected PsiElement doReplacementInJava(@NotNull final PsiFile psiFile,
                                           @NotNull final Editor editor,
                                           @Nullable PsiLiteralExpression literalExpression,
                                           String i18nizedText) throws IncorrectOperationException {
    PsiPolyadicExpression concatenation = getEnclosingLiteralConcatenation(psiFile, editor);
    assert concatenation != null;
    PsiExpression expression = JavaPsiFacade.getInstance(psiFile.getProject()).getElementFactory().createExpressionFromText(i18nizedText, concatenation);
    return concatenation.replace(expression);
  }

  private static String composeParametersText(final List<PsiExpression> args) {
    return args.stream().map(PsiExpression::getText).collect(Collectors.joining(","));
  }

  @Override
  protected JavaI18nizeQuickFixDialog createDialog(final Project project, final PsiFile context, final PsiLiteralExpression literalExpression) {
    PsiPolyadicExpression concatenation = getEnclosingLiteralConcatenation(literalExpression);
    String formatString = "";
    final List<PsiExpression> args = new ArrayList<>();
    try {
      formatString = StringUtil.escapeStringCharacters(PsiConcatenationUtil.buildFormatString(concatenation, false, args));
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }

    return new JavaI18nizeQuickFixDialog(project, context, literalExpression, formatString, null, true, true) {
      @Override
      @Nullable
      protected String getTemplateName() {
        return myResourceBundleManager.getConcatenationTemplateName();
      }

      @Override
      protected String generateText(final I18nizedTextGenerator textGenerator, final String propertyKey, final PropertiesFile propertiesFile,
                                    final PsiLiteralExpression literalExpression) {
        return textGenerator.getI18nizedConcatenationText(propertyKey, composeParametersText(args), propertiesFile, literalExpression);
      }

      @Override
      public PsiExpression[] getParameters() {
        return args.toArray(PsiExpression.EMPTY_ARRAY);
      }

      @Override
      protected void addAdditionalAttributes(final Map<String, String> attributes) {
        attributes.put(PARAMETERS_OPTION_KEY, composeParametersText(args));
      }
    };
  }

  @Nullable
  private static PsiPolyadicExpression getEnclosingLiteralConcatenation(@NotNull PsiFile file, @NotNull Editor editor) {
    final PsiElement elementAt = file.findElementAt(editor.getCaretModel().getOffset());
    return getEnclosingLiteralConcatenation(elementAt);
  }

  @Nullable
  static PsiPolyadicExpression getEnclosingLiteralConcatenation(final PsiElement psiElement) {
    PsiPolyadicExpression element = PsiTreeUtil.getParentOfType(psiElement, PsiPolyadicExpression.class, false, PsiMember.class);
    if (element == null) return null;

    PsiPolyadicExpression concatenation = null;
    boolean stringLiteralOccured = false;
    while (true) {
      if (element.getOperationTokenType() != JavaTokenType.PLUS) return concatenation;
      for (PsiExpression operand : element.getOperands()) {
        stringLiteralOccured |= operand instanceof PsiLiteralExpression && ((PsiLiteralExpression)operand).getValue() instanceof String;
        if (stringLiteralOccured) {
          break;
        }
      }

      if (stringLiteralOccured) {
        concatenation = element;
      }
      PsiElement parent = element.getParent();
      if (!(parent instanceof PsiPolyadicExpression)) return concatenation;
      element = (PsiPolyadicExpression) parent;
    }
  }

  private static PsiLiteralExpression getContainingLiteral(final PsiPolyadicExpression concatenation) {
    for (PsiExpression operand : concatenation.getOperands()) {
      if (operand instanceof PsiLiteralExpression) {
        return (PsiLiteralExpression)operand;
      }
    }
    return null;
  }
}

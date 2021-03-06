// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:JvmName("UastReferenceRegistrar")

package com.intellij.psi

import com.intellij.openapi.util.Key
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.StandardPatterns
import com.intellij.patterns.uast.UElementPattern
import com.intellij.patterns.uast.capture
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.patterns.uast.uExpression
import com.intellij.psi.impl.search.LowLevelSearchUtil
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ProcessingContext
import com.intellij.util.SmartList
import com.intellij.util.text.StringSearcher
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.uast.*
import org.jetbrains.uast.expressions.UInjectionHost

fun PsiReferenceRegistrar.registerUastReferenceProvider(pattern: (UElement, ProcessingContext) -> Boolean,
                                                        provider: UastReferenceProvider,
                                                        priority: Double = PsiReferenceRegistrar.DEFAULT_PRIORITY) {
  this.registerReferenceProvider(adaptPattern(pattern, provider.supportedUElementTypes),
                                 UastReferenceProviderAdapter(provider),
                                 priority)
}

fun PsiReferenceRegistrar.registerUastReferenceProvider(pattern: ElementPattern<out UElement>,
                                                        provider: UastReferenceProvider,
                                                        priority: Double = PsiReferenceRegistrar.DEFAULT_PRIORITY) {
  this.registerReferenceProvider(adaptPattern(pattern::accepts, provider.supportedUElementTypes),
                                 UastReferenceProviderAdapter(provider), priority)
}

fun uastInjectionHostReferenceProvider(provider: (UExpression, PsiLanguageInjectionHost) -> Array<PsiReference>): UastInjectionHostReferenceProvider =
  object : UastInjectionHostReferenceProvider() {

    override fun getReferencesForInjectionHost(uExpression: UExpression,
                                               host: PsiLanguageInjectionHost,
                                               context: ProcessingContext): Array<PsiReference> = provider(uExpression, host)

    override fun toString(): String = "uastInjectionHostReferenceProvider($provider)"
  }

fun <T : UElement> uastReferenceProvider(cls: Class<T>, provider: (T, PsiElement) -> Array<PsiReference>): UastReferenceProvider =
  object : UastReferenceProvider(cls) {

    override fun getReferencesByElement(element: UElement, context: ProcessingContext): Array<PsiReference> =
      provider(cls.cast(element), context[REQUESTED_PSI_ELEMENT])

    override fun toString(): String = "uastReferenceProvider($provider)"
  }

inline fun <reified T : UElement> uastReferenceProvider(noinline provider: (T, PsiElement) -> Array<PsiReference>): UastReferenceProvider =
  uastReferenceProvider(T::class.java, provider)

private val cachedUElement = Key.create<UElement>("UastReferenceRegistrar.cachedUElement")
internal val REQUESTED_PSI_ELEMENT = Key.create<PsiElement>("REQUESTED_PSI_ELEMENT")
internal val USAGE_PSI_ELEMENT = Key.create<PsiElement>("USAGE_PSI_ELEMENT")

internal fun getOrCreateCachedElement(element: PsiElement,
                                      context: ProcessingContext?,
                                      supportedUElementTypes: List<Class<out UElement>>): UElement? =
  element as? UElement ?: context?.get(cachedUElement) ?: supportedUElementTypes.asSequence().mapNotNull {
    element.toUElement(it)
  }.firstOrNull()?.also { context?.put(cachedUElement, it) }

private fun adaptPattern(
  predicate: (UElement, ProcessingContext) -> Boolean,
  supportedUElementTypes: List<Class<out UElement>>
): ElementPattern<out PsiElement> {
  val uastPatternAdapter = UastPatternAdapter(predicate, supportedUElementTypes)

  // optimisation until IDEA-211738 is implemented
  if (supportedUElementTypes == listOf(UInjectionHost::class.java)) {
    return StandardPatterns.instanceOf(PsiLanguageInjectionHost::class.java).and(uastPatternAdapter)
  }

  return uastPatternAdapter
}

fun ElementPattern<out UElement>.asPsiPattern(vararg supportedUElementTypes: Class<out UElement>): ElementPattern<PsiElement> = UastPatternAdapter(
  this::accepts,
  if (supportedUElementTypes.isNotEmpty()) supportedUElementTypes.toList() else listOf(UElement::class.java)
)

/**
 * Creates UAST reference provider that accepts additional PSI element that could be either is the same as reference PSI element or
 * reference element that is used in the same file and satisfy usage pattern.
 *
 * @see registerReferenceProviderByUsage
 */
@ApiStatus.Experimental
fun uastByUsageReferenceProvider(provider: (expr: UExpression, referencePsi: PsiElement, usagePsi: PsiElement) -> Array<PsiReference>): UastReferenceProvider =
  object : UastReferenceProvider(UExpression::class.java) {
    override fun getReferencesByElement(element: UElement, context: ProcessingContext): Array<PsiReference> {
      val usagePsi = context[USAGE_PSI_ELEMENT] ?: context[REQUESTED_PSI_ELEMENT]

      return provider(UExpression::class.java.cast(element), context[REQUESTED_PSI_ELEMENT], usagePsi)
    }

    override fun toString(): String = "uastByUsageReferenceProvider($provider)"
  }

/**
 * Registers a provider that will be called on the expressions that directly satisfy the [usagePattern] or at least one of the expression
 * usages satisfies the pattern if it was assigned to a variable. Search of variable usages performed only for expressions that satisfy
 * [expressionPattern]. There are standard expression patterns for usage search: [uInjectionHostInVariable] and [uExpressionInVariable].
 *
 * Consider using [uastByUsageReferenceProvider] if you need to obtain additional context from a usage place.
 */
@ApiStatus.Experimental
fun PsiReferenceRegistrar.registerReferenceProviderByUsage(expressionPattern: UElementPattern<*, *>,
                                                           usagePattern: UElementPattern<*, *>,
                                                           provider: UastReferenceProvider,
                                                           priority: Double = PsiReferenceRegistrar.DEFAULT_PRIORITY) {
  this.registerUastReferenceProvider(usagePattern, provider, priority)

  this.registerUastReferenceProvider(expressionPattern, object : UastReferenceProvider(UExpression::class.java) {
    override fun acceptsTarget(target: PsiElement): Boolean {
      return !target.project.isDefault && provider.acceptsTarget(target)
    }

    override fun getReferencesByElement(element: UElement, context: ProcessingContext): Array<PsiReference> {
      val parentVariable = when (val uastParent = element.uastParent) {
        is UVariable -> uastParent
        is UPolyadicExpression -> uastParent.uastParent as? UVariable // support .withUastParentOrSelf() patterns
        else -> null
      }

      if (parentVariable == null || !parentVariable.type.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
        return PsiReference.EMPTY_ARRAY
      }

      val usage = getDirectVariableUsages(parentVariable).find { usage ->
        val refExpression = usage.toUElementOfType<UReferenceExpression>()
        refExpression != null && usagePattern.accepts(refExpression, context)
      } ?: return PsiReference.EMPTY_ARRAY

      context.put(USAGE_PSI_ELEMENT, usage)

      return provider.getReferencesByElement(element, context)
    }

    override fun toString(): String = "uastReferenceByUsageAdapter($provider)"
  }, priority)
}

@ApiStatus.Experimental
fun uInjectionHostInVariable() = injectionHostUExpression().withUastParent(capture(UVariable::class.java))
@ApiStatus.Experimental
fun uExpressionInVariable() = uExpression().withUastParent(capture(UElement::class.java).withUastParentOrSelf(capture(UVariable::class.java)))

private fun getDirectVariableUsages(uVar: UVariable): List<PsiElement> {
  val variablePsi = uVar.sourcePsi ?: return emptyList()
  return CachedValuesManager.getManager(variablePsi.project).getCachedValue(variablePsi, CachedValueProvider {
    Result.createSingleDependency(findDirectVariableUsages(variablePsi), PsiModificationTracker.MODIFICATION_COUNT)
  })
}

private fun findDirectVariableUsages(variablePsi: PsiElement): List<PsiElement> {
  val uVariable = variablePsi.toUElementOfType<UVariable>() ?: return emptyList()
  val variableName = uVariable.name ?: return emptyList()
  val file = variablePsi.containingFile ?: return emptyList()

  val fileContent = file.viewProvider.contents
  val searcher = StringSearcher(variableName, true, true)
  val usages = SmartList<PsiElement>()

  LowLevelSearchUtil.processTextOccurrences(fileContent, 0, fileContent.length, searcher) { offset ->
    val occurrencePsi = file.findElementAt(offset)
    if (occurrencePsi != null) {
      // we get identifiers and need to process their parents, see IDEA-232166
      val uRef = occurrencePsi.parent.findContaining(UReferenceExpression::class.java)
      val expressionType = uRef?.getExpressionType()
      if (expressionType != null && expressionType.equalsToText(CommonClassNames.JAVA_LANG_STRING)) {
        val occurrenceResolved = uRef.tryResolve().toUElement()?.sourcePsi
        if (occurrenceResolved != null
            && PsiManager.getInstance(occurrencePsi.project).areElementsEquivalent(occurrenceResolved, variablePsi)) {
          usages.add(uRef.sourcePsi)
        }
      }
    }
    true
  }

  return if (usages.isEmpty()) emptyList() else usages
}
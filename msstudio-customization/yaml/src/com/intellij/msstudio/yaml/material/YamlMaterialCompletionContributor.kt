// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.yaml.material

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.msstudio.ide.customization.server.core.ServerCoreService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Completes the values of the `material` keys in YAML files.
 *
 * A plugin config often names a Bukkit material in a `material` key.
 * The contributor suggests the materials of the detected server core,
 * so the list matches the game version of the project.
 */
class YamlMaterialCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    val position = parameters.position
    findMaterialKey(position) ?: return
    val core = ServerCoreService.getInstance(position.project).detectedCore
    val materials = MaterialRegistry.getMaterials(core)
    if (materials.isEmpty()) return
    val version = core?.minecraftVersion
    for (name in materials) {
      result.addElement(materialElement(name, version))
    }
  }

  private fun materialElement(name: String, version: String?): LookupElementBuilder {
    return LookupElementBuilder.create(name)
      .withTypeText(version, true)
      .withLookupString(name.lowercase())
  }

}

/**
 * Returns the `material` key that owns [position], or null.
 * It returns null when the caret sits on the key itself.
 */
fun findMaterialKey(position: PsiElement): YAMLKeyValue? {
  val keyValue = position.parentOfType<YAMLKeyValue>(true) ?: return null
  if (!keyValue.keyText.equals("material", ignoreCase = true)) return null
  if (keyValue.key?.textRange?.contains(position.textRange) == true) return null
  return keyValue
}
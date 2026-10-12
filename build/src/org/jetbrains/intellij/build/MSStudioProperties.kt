// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Path

/**
 * The MSStudio product.
 *
 * MSStudio is an IntelliJ Platform IDE for Minecraft servers. It extends the IDEA Community
 * product and adds Minecraft feature modules to it.
 */
open class MSStudioProperties(communityHomeDir: Path) : IdeaCommunityProperties(communityHomeDir) {
  init {
    platformPrefix = "MSStudio"
    applicationInfoModule = "intellij.msstudio.customization"

    // The module that carries the product identity: the application info and the product plugin descriptor.
    productLayout.productImplementationModules += "intellij.msstudio.customization"
  }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "MSStudio${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    include(intellijCommunityBaseFragment(platformPrefix))

    // The content module of the MSStudio welcome screen: the Open and New items of the left panel.
    // A content module of its own, because the jar of the application-info module carries the product descriptor.
    embeddedModule("intellij.msstudio.ide.customization")
    // The Minecraft feature modules of the MSStudio product land here, as a module set or as modules.
  }
}
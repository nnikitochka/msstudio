// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.plus
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.CommunityProductFragments
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Files
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

    // Start from the plugin set of IDEA Community and drop the plugins the product does not ship.
    // The Maven plugin builds no Minecraft server plugin, and it embeds a full Maven distribution.
    // The Android Gradle plugins serve Android development only.
    // The rest is weight trimming for a Minecraft-focused IDE: no embedded browser (JCEF),
    // no grammar checker, no Compose tooling, no diagrams, no diagnostics or AI-server plugins,
    // no Gradle, no test runner integrations, no Git integration, no JavaFX scene builder,
    // no DevKit for IDE plugin development, no Eclipse project import, no configuration-as-script,
    // and no issue-tracker host without the trackers.
    // The Aether resolver and the platform test runner stay: modules of the Java plugin
    // (intellij.java.ui, intellij.java.execution.impl, intellij.java.vcs) depend on them,
    // so their removal breaks the startup of the product.
    // Kotlin and Groovy stay: the user runs Kotlin and Groovy scripts in the IDE at runtime.
    productLayout.bundledPluginModules = IDEA_BUNDLED_PLUGINS
      .removing("intellij.maven.plugin")
      .removing("intellij.android.gradle.declarative.lang.ide")
      .removing("intellij.android.gradle.dsl")
      .removing("intellij.jcef.plugin")
      .removing("intellij.grazie")
      .removing("intellij.compose.ide.plugin")
      .removing("intellij.platform.compose.plugin")
      .removing("intellij.mermaid")
      .removing("intellij.performanceTesting")
      .removing("intellij.mcpserver.plugin")
      .removing("intellij.featuresTrainer")
      .removing("intellij.grid.plugin")
      .removing("intellij.gradle.plugin")
      .removing("intellij.gradle.java.plugin")
      .removing("intellij.junit")
      .removing("intellij.testng")
      .removing("intellij.java.coverage")
      .removing("intellij.vcs.git")
      .removing("intellij.vcs.git.commit.modal")
      .removing("intellij.vcs.github")
      .removing("intellij.vcs.gitlab")
      .removing("intellij.dev")
      .removing("intellij.eclipse")
      .removing("intellij.configurationScript")
      .removing("intellij.copyright")
      .removing("intellij.html.tools")
      .removing("intellij.java.i18n")
      .removing("intellij.java.byteCodeViewer")
      .removing("intellij.platform.structuralSearch.plugin")
      .removing("intellij.platform.tasks.plugin") + sequenceOf(
      "intellij.idea.customization.plugin",
      "intellij.msstudio.yaml",
      "intellij.msstudio.language.pack.ru",
    )

    // Do not build the Android plugin at all: it is neither bundled nor published for MSStudio.
    productLayout.pluginLayouts = lazy {
      getCommunityRepositoryPlugins() + persistentListOf(
        JavaPluginLayout.javaPlugin(),
        groovyPlugin()
      )
    }

    // The class-version rules of removed plugins would match no files.
    // Keep the rules of the plugins the product still ships.
    versionCheckerConfig = CE_CLASS_VERSIONS.filterKeys { path ->
      !path.startsWith("plugins/maven-plugin/") &&
      !path.startsWith("plugins/java-coverage/") &&
      !path.startsWith("plugins/junit/") &&
      !path.startsWith("plugins/gradle-plugin/")
    }
  }

  override fun copyAdditionalFiles(targetDir: Path, context: BuildContext) {
    super.copyAdditionalFiles(targetDir, context)

    // The product ships no applet support. Drop the leftover applet-viewer policy file
    // that the IDEA Community bin configuration carries.
    Files.deleteIfExists(targetDir.resolve("bin").resolve("appletviewer.policy"))
  }

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "MSStudio${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override val baseFileName: String
    get() = "msstudio"

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String = "MSStudio-$buildNumber"

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "msstudio"

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer =
    ideaCommunityLinuxCustomizer(projectHome) {
      iconPngPath = "msstudio-customization/resources/msstudio_128.png"
      iconPngPathForEAP = "msstudio-customization/resources/msstudio_128.png"
      rootDirectoryName { _, _ -> "MSStudio" }
    }

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer =
    ideaCommunityWindowsCustomizer(projectHome) {
      icoPath = "msstudio-customization/resources/msstudio.ico"
      icoPathForEAP = "msstudio-customization/resources/msstudio.ico"
      fullName { "MSStudio" }
      installDirNameHandler { "MSStudio" }
    }

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    include(msStudioBaseFragment())

    // The content module of the MSStudio welcome screen: the Open and New items of the left panel.
    // A content module of its own, because the jar of the application-info module carries the product descriptor.
    // It loads by a classloader of its own: the server console links the classes of the terminal module,
    // and an embedded module cannot reach a module of the modular classloader system.
    requiredModule("intellij.msstudio.ide.customization")
    // The Minecraft feature modules of the MSStudio product land here, as a module set or as modules.
  }
}

private fun msStudioBaseFragment(): ProductModulesContentSpec = productModules {
  alias("com.intellij.modules.idea")
  alias("com.intellij.modules.idea.community")
  alias("com.intellij.modules.java-capable")
  alias("com.intellij.modules.python-core-capable")
  alias("com.intellij.modules.python-in-non-pycharm-ide-capable")
  alias("com.intellij.platform.ide.provisioner")
  include(CommunityProductFragments.platformCoreFragment())
  alias("com.intellij.modules.all")

  requiredModule("intellij.java.ide.resources")
  module("intellij.java.ide.newUiOnboarding")
  module("intellij.xml.xmlbeans")
  module("intellij.libraries.log4j.to.slf4j")
  module("intellij.libraries.xmlbeans")
  module("intellij.platform.ide.newUiOnboarding")
  module("intellij.platform.ide.newUsersOnboarding")
  module("intellij.ide.startup.importSettings")
  privateModule("intellij.libraries.sqlite")
  module("intellij.platform.customization.min")

  moduleSet(CommunityModuleSets.essential())
  moduleSet(CommunityModuleSets.debugger())
  // The Java plugin links this platform API even when no build-system plugin is bundled.
  moduleSet(CommunityModuleSets.externalSystem())
  moduleSet(CommunityModuleSets.spellchecker())
  moduleSet(CommunityModuleSets.settingsSync())
  moduleSet(CommunityModuleSets.polySymbols())
  moduleSet(CommunityModuleSets.vcs())
  moduleSet(CommunityModuleSets.lsp())
  moduleSet(CommunityModuleSets.xmlRuntime())
  moduleSet(CommunityModuleSets.platformResourceDefaults())

  module("intellij.platform.lvcs.impl")
  module("intellij.platform.diagnostic.freezeAnalyzer")
  module("intellij.platform.warmup")
  module("intellij.platform.inspect")
  // The Java compiler module requires Build View even when no build-system plugin is bundled.
  module("intellij.platform.buildView")
  module("intellij.platform.buildView.backend")
  module("intellij.platform.buildView.frontend")
  // The Java implementation requires Project View. MSStudio hides its tool window.
  module("intellij.platform.projectView")
  module("intellij.platform.projectView.backend")
  module("intellij.platform.projectView.frontend")
  // Properties, which Groovy requires, depends on this analysis module.
  module("intellij.platform.duplicates.analysis")
  module("intellij.regexp")
  module("intellij.spellchecker.vcs")
  module("intellij.platform.ide.impl.wsl")
  module("intellij.platform.diagnostic.telemetry.agent.extension")
  module("intellij.platform.langInjection")
  module("intellij.platform.langInjection.backend")
  module("intellij.platform.versionDownloadManager")
  module("intellij.platform.registry.ui")
  module("intellij.platform.ide.scripting")

  embeddedModule("intellij.idea.community.ide.customization")
}

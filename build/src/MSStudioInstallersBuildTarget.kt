// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.BuildLifetime
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.MSStudioProperties
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.buildCommunityStandaloneJpsBuilder
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext
import org.jetbrains.intellij.build.telemetry.TraceManager.spanBuilder
import org.jetbrains.intellij.build.telemetry.use

@ApiStatus.Internal
object MSStudioInstallersBuildTarget {
  val OPTIONS: BuildOptions = BuildOptions().apply {
    incrementalCompilation = true
    useCompiledClassesFromProjectOutput = false
    buildStepsToSkip += BuildOptions.MAC_SIGN_STEP
    buildStepsToSkip += BuildOptions.WIN_SIGN_STEP
    if (OsFamily.currentOs == OsFamily.MACOS) {
      buildStepsToSkip += BuildOptions.WINDOWS_EXE_INSTALLER_STEP
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    BuildLifetime().use { lifetime ->
      val options = OPTIONS.copy(buildStepsToSkip = OPTIONS.buildStepsToSkip)
      val projectHome = COMMUNITY_ROOT.communityRoot
      val context = createBuildContext(
        projectHome = projectHome,
        productProperties = MSStudioProperties(projectHome),
        setupTracer = true,
        options = options,
        lifetime = lifetime,
      )
      context.compileModules(moduleNames = null, includingTestsInModules = listOf("intellij.platform.jps.build.tests"))
      buildDistributions(context)
      spanBuilder("build standalone JPS").use {
        buildCommunityStandaloneJpsBuilder(targetDir = context.paths.artifactDir.resolve("jps"), context)
      }
    }
  }
}

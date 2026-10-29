// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.msstudio.ide.customization.server.core

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * Holds the server core of the project.
 *
 * It scans the base folder of the project once on start. It rescans
 * the folder on user request. It notifies listeners on each change.
 *
 * The MSStudio YAML plugin reads the detected core through this service.
 */
@Service(Service.Level.PROJECT)
class ServerCoreService(private val project: Project) : Disposable {
  @Volatile
  var detectedCore: ServerCore? = null
    private set

  /**
   * Scans the base folder and updates the stored core. It runs on a background thread.
   */
  fun refresh(): ServerCore? {
    val basePath = project.basePath?.let(Path::of)
    val core = basePath?.let(ServerCoreDetector::detect)
    detectedCore = core
    project.messageBus.syncPublisher(TOPIC).coreChanged(core)
    ActivityTracker.getInstance().inc()
    return core
  }

  override fun dispose() {
  }

  interface Listener {
    fun coreChanged(core: ServerCore?)
  }

  companion object {
    val TOPIC: Topic<Listener> = Topic.create("MSStudio server core", Listener::class.java)

    fun getInstance(project: Project): ServerCoreService = project.getService(ServerCoreService::class.java)
  }
}

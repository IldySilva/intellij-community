// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.hotswap

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.isInsideMainEditor
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebuggerBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

private class HotSwapWithRebuildAction : AnAction(), CustomComponentAction {
  var inProgress = false
  var session: HotSwapSession<*>? = null

  override fun actionPerformed(e: AnActionEvent) {
    val session = session ?: return
    session.startHotSwap()
    callWithTemplate(e.dataContext, session)
  }

  private fun <S> callWithTemplate(context: DataContext, session: HotSwapSession<S>) {
    session.provider.performHotSwap(context, session)
  }

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return HotSwapToolbarComponent(this, presentation, place)
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    (component as HotSwapToolbarComponent).update(inProgress, presentation)
  }
}

private class HotSwapToolbarComponent(action: AnAction, presentation: Presentation, place: String)
  : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {

  val button = ActionButton(action, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)

  init {
    isOpaque = false
    add(JBLabel(XDebuggerBundle.message("xdebugger.hotswap.code.changed")))
    add(button)
  }

  fun update(inProgress: Boolean, presentation: Presentation) {
    presentation.isEnabled = !inProgress
    presentation.icon = if (inProgress) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Rebuild
    // Force animation in the disabled state
    presentation.disabledIcon = presentation.icon
  }
}

internal class HotSwapFloatingToolbarProvider : FloatingToolbarProvider {
  override val autoHideable: Boolean get() = false
  private val hotSwapAction by lazy { HotSwapWithRebuildAction() }

  override val actionGroup: ActionGroup by lazy { DefaultActionGroup(hotSwapAction) }

  override fun isApplicable(dataContext: DataContext): Boolean =
    Registry.`is`("debugger.hotswap.floating.toolbar")
    && isInsideMainEditor(dataContext)

  override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
    val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
    val instance = HotSwapSessionManager.getInstance(project)

    instance.addListener(ChangesListener(component), parentDisposable)
  }

  private inner class ChangesListener(private val component: FloatingToolbarComponent) : HotSwapChangesListener {
    override fun onStatusChanged(session: HotSwapSession<*>, status: HotSwapVisibleStatus) {
      if (status == HotSwapVisibleStatus.IN_PROGRESS) {
        hotSwapAction.inProgress = true
        return
      }

      val action = when (status) {
        HotSwapVisibleStatus.NO_CHANGES -> HotSwapButtonAction.HIDE
        HotSwapVisibleStatus.CHANGES_READY -> HotSwapButtonAction.SHOW
        HotSwapVisibleStatus.SESSION_COMPLETED -> HotSwapButtonAction.HIDE_NOW
        else -> error("Unexpected status $status")
      }
      if (action == HotSwapButtonAction.SHOW) {
        hotSwapAction.inProgress = false
        hotSwapAction.session = session
      } else {
        hotSwapAction.session = null
      }
      updateComponentIfNeeded(component, session.coroutineScope, action)
    }
  }

  private fun updateComponentIfNeeded(component: FloatingToolbarComponent, scope: CoroutineScope, show: HotSwapButtonAction) {
    // We need to hide the button even if the coroutineScope is cancelled
    scope.launch(Dispatchers.EDT, start = CoroutineStart.ATOMIC) {
      when (show) {
        HotSwapButtonAction.SHOW -> component.scheduleShow()
        HotSwapButtonAction.HIDE -> component.scheduleHide()
        HotSwapButtonAction.HIDE_NOW -> component.hideImmediately()
      }
    }
  }
}

private enum class HotSwapButtonAction {
  SHOW, HIDE, HIDE_NOW
}

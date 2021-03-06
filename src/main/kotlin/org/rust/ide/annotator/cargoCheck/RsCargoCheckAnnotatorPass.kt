/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator.cargoCheck

import com.intellij.codeHighlighting.DirtyScopeTrackingHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.*
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.AnyPsiChangeListener
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.util.messages.MessageBus
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.containingCargoPackage

class RsCargoCheckAnnotatorPass(
    private val factory: RsCargoCheckAnnotatorPassFactory,
    private val file: PsiFile,
    private val editor: Editor
) : TextEditorHighlightingPass(file.project, editor.document), DumbAware {
    private val annotationHolder: AnnotationHolderImpl = AnnotationHolderImpl(AnnotationSession(file))
    private var annotationInfo: Lazy<RsCargoCheckAnnotationResult?>? = null
    private val annotationResult: RsCargoCheckAnnotationResult? get() = annotationInfo?.value
    private lateinit var disposable: Disposable

    override fun doCollectInformation(progress: ProgressIndicator) {
        annotationHolder.clear()
        if (file !is RsFile || !isAnnotationPassEnabled) return

        val cargoPackage = file.containingCargoPackage
        if (cargoPackage?.origin != PackageOrigin.WORKSPACE) return

        val project = file.project
        disposable = createDisposableOnAnyPsiChange(project.messageBus).also {
            Disposer.register(ModuleUtil.findModuleForFile(file) ?: project, it)
        }

        annotationInfo = RsCargoCheckUtils.checkLazily(
            project.toolchain ?: return,
            project,
            disposable,
            cargoPackage.workspace.contentRoot,
            cargoPackage.name,
            isOnFly = true
        )
    }

    override fun doApplyInformationToEditor() {
        if (annotationInfo == null || !isAnnotationPassEnabled) return

        val update = object : Update(file) {
            override fun run() {
                BackgroundTaskUtil.runUnderDisposeAwareIndicator(disposable, Runnable {
                    val annotationResult = annotationResult ?: return@Runnable
                    runReadAction {
                        ProgressManager.checkCanceled()
                        doApply(annotationResult)
                        ProgressManager.checkCanceled()
                        doFinish(highlights)
                    }
                })
            }

            override fun canEat(update: Update?): Boolean = true
        }

        factory.scheduleExternalActivity(update)
    }

    private fun doApply(annotationResult: RsCargoCheckAnnotationResult) {
        if (file !is RsFile || !file.isValid) return
        try {
            annotationHolder.createAnnotationsForFile(file, annotationResult)
        } catch (t: Throwable) {
            if (t is ProcessCanceledException) throw t
            LOG.error(t)
        }
    }

    private fun doFinish(highlights: List<HighlightInfo>) {
        val document = document ?: return
        ApplicationManager.getApplication().invokeLater({
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject,
                document,
                0,
                file.textLength,
                highlights,
                colorsScheme,
                id
            )
            DaemonCodeAnalyzerEx.getInstanceEx(myProject).fileStatusMap.markFileUpToDate(document, id)
        }, ModalityState.stateForComponent(editor.component))
    }

    private val highlights: List<HighlightInfo>
        get() = annotationHolder.map(HighlightInfo::fromAnnotation)

    private val isAnnotationPassEnabled: Boolean
        get() = file.project.rustSettings.useCargoCheckAnnotator

    companion object {
        private val LOG: Logger = Logger.getInstance(RsCargoCheckAnnotatorPass::class.java)
    }
}

class RsCargoCheckAnnotatorPassFactory(
    project: Project,
    registrar: TextEditorHighlightingPassRegistrar
) : DirtyScopeTrackingHighlightingPassFactory {
    private val myPassId: Int = registrar.registerTextEditorHighlightingPass(
        this,
        null,
        null,
        false,
        -1
    )

    private val cargoCheckQueue = MergingUpdateQueue(
        "CargoCheckQueue",
        TIME_SPAN,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        project,
        null,
        false
    )

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        FileStatusMap.getDirtyTextRange(editor, passId) ?: return null
        return RsCargoCheckAnnotatorPass(this, file, editor)
    }

    override fun getPassId(): Int = myPassId

    fun scheduleExternalActivity(update: Update) = cargoCheckQueue.queue(update)

    companion object {
        private const val TIME_SPAN: Int = 300
    }
}

private fun createDisposableOnAnyPsiChange(messageBus: MessageBus): Disposable {
    val child = Disposer.newDisposable("Dispose on PSI change")
    messageBus.connect(child).subscribe(
        PsiManagerImpl.ANY_PSI_CHANGE_TOPIC,
        object : AnyPsiChangeListener.Adapter() {
            override fun beforePsiChanged(isPhysical: Boolean) {
                Disposer.dispose(child)
            }
        }
    )
    return child
}

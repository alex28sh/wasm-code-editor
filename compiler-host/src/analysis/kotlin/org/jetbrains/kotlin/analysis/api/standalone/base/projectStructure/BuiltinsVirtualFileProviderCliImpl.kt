package org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProviderBaseImpl
import org.jetbrains.kotlin.psi.KtPlatformInterface
import java.net.URL

/**
 * Replaces the class of the same name in the Analysis API, which finds the `.kotlin_builtins` files as entries of
 * the jar it was loaded from.
 *
 * In the image they are resources that are in no jar, so it found none and warned about each of them on every
 * request. They are the built-in declarations of the JVM; a Wasm module takes its own from the standard library
 * klib. So there are none here, on the JVM as well, where the scenario runs.
 */
@OptIn(KtPlatformInterface::class)
@KaImplementationDetail
class BuiltinsVirtualFileProviderCliImpl : BuiltinsVirtualFileProviderBaseImpl() {
    override fun findVirtualFile(url: URL): VirtualFile? = null

    override fun getBuiltinVirtualFiles(): Set<VirtualFile> = emptySet()
}

/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator.fixes

import org.intellij.lang.annotations.Language
import org.rust.cargo.RsWithToolchainTestBase
import org.rust.cargo.project.settings.rustSettings
import org.rust.fileTree
import org.rust.replaceCaretMarker

class ApplySuggestionFixTest : RsWithToolchainTestBase() {

    override fun setUp() {
        super.setUp()
        project.rustSettings.data = project.rustSettings.data.copy(useCargoCheckAnnotator = true)
    }

    fun `test rustc suggestion (machine applicable)`() = checkFixByText("""
        fn main() {
            let <weak_warning>x</weak_warning> = 0;
        }
    """, """
        fn main() {
            let _x = 0;
        }
    """)

    fun `test rustc suggestion (maybe incorrect)`() = checkFixByText("""
        fn main() {
            if <error>1 <- 2</error> {}
        }
    """, """
        fn main() {
            if 1< -2 {}
        }
    """)

    fun `test rustc suggestion (has placeholders)`() = checkFixByText("""
        struct S { x: i32, y: i32 }
        impl S {
            fn new() -> Self { <error>Self</error> }
        }
    """, """
        struct S { x: i32, y: i32 }
        impl S {
            fn new() -> Self { Self { /* fields */ } }
        }
    """)

    fun `test rustc suggestion (unspecified)`() = checkFixByText("""
        fn foo<'a>(x: &i32, y: &'a i32) -> &'a i32 {
            if x > y { <error>x</error> } else { y }
        }
    """, """
        fn foo<'a>(x: &'a i32, y: &'a i32) -> &'a i32 {
            if x > y { x } else { y }
        }
    """)

    private fun checkFixByText(@Language("Rust") before: String, @Language("Rust") after: String) {
        fileTree {
            toml("Cargo.toml", """
                [package]
                name = "hello"
                version = "0.1.0"
                authors = []
            """)

            dir("src") {
                file("main.rs", before)
            }
        }.create()
        val filePath = "src/main.rs"
        myFixture.openFileInEditor(cargoProjectDirectory.findFileByRelativePath(filePath)!!)
        myFixture.checkHighlighting()
        val action = myFixture.getAllQuickFixes(filePath)
            .singleOrNull { it.text.startsWith("Rustc: ") }
            ?: return // BACKCOMPAT: Rust ???
        myFixture.launchAction(action)
        myFixture.checkResult(replaceCaretMarker(after.trimIndent()))
    }
}

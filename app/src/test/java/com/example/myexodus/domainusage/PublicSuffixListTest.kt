package com.example.myexodus.domainusage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U-2: exact, wildcard and exception rules; co.ke true, example.co.ke false. */
class PublicSuffixListTest {

    @Test
    fun exactRuleMatchesItselfOnly() {
        val psl = PublicSuffixList.parse(listOf("com"))
        assertTrue(psl.isPublicSuffix("com"))
        assertFalse(psl.isPublicSuffix("example.com"))
    }

    @Test
    fun wildcardRuleMatchesExactlyOneExtraLabel() {
        val psl = PublicSuffixList.parse(listOf("*.ck"))
        assertTrue(psl.isPublicSuffix("a.ck"))
        assertFalse(psl.isPublicSuffix("b.a.ck"))
        assertFalse(psl.isPublicSuffix("ck"))
    }

    @Test
    fun exceptionRuleOverridesWildcard() {
        val psl = PublicSuffixList.parse(listOf("*.ck", "!www.ck"))
        assertFalse(psl.isPublicSuffix("www.ck"))
        assertTrue(psl.isPublicSuffix("a.ck"))
    }

    @Test
    fun coKeIsAPublicSuffixButExampleCoKeIsNot() {
        val psl = PublicSuffixList.parse(listOf("ke", "co.ke"))
        assertTrue(psl.isPublicSuffix("co.ke"))
        assertFalse(psl.isPublicSuffix("example.co.ke"))
    }

    @Test
    fun commentsBlankLinesAndCaseAreIgnored() {
        val psl = PublicSuffixList.parse(listOf("// a comment", "  ", "COM  // inline", "co.KE"))
        assertTrue(psl.isPublicSuffix("com"))
        assertTrue(psl.isPublicSuffix("CO.KE"))
        assertFalse(psl.isPublicSuffix("example.co.ke"))
    }
}

/** U-2's load-shape requirement: the pinned asset answers synchronously, no lazy init. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PublicSuffixListAssetTest {

    @Test
    fun pinnedAssetLoadsSynchronously() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val psl = PublicSuffixList.fromAsset(context)
        assertTrue(psl.isPublicSuffix("co.ke"))
        assertFalse(psl.isPublicSuffix("example.co.ke"))
        assertTrue(psl.isPublicSuffix("com"))
        assertFalse(psl.isPublicSuffix("example.com"))
    }
}

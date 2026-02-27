package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase

class ProjectResolverUnitTest : TestCase() {

    fun testNormalizePathRemovesTrailingSlash() {
        assertEquals("/home/user/project", ProjectResolver.normalizePath("/home/user/project/"))
        assertEquals("/home/user/project", ProjectResolver.normalizePath("/home/user/project"))
    }

    fun testNormalizePathConvertsBackslashes() {
        assertEquals("C:/Users/project", ProjectResolver.normalizePath("C:\\Users\\project"))
        assertEquals("C:/Users/project", ProjectResolver.normalizePath("C:\\Users\\project\\"))
    }

    fun testNormalizePathHandlesEmpty() {
        assertEquals("", ProjectResolver.normalizePath(""))
    }

    fun testNormalizePathHandlesMixedSeparators() {
        assertEquals("C:/Users/project/src", ProjectResolver.normalizePath("C:\\Users/project\\src/"))
    }
}

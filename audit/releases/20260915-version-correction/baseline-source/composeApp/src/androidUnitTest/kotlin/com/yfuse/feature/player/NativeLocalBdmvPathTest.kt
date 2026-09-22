package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeLocalBdmvPathTest {
    @Test
    fun normal_relative_bdmv_paths_are_canonicalized_without_touching_case() {
        assertEquals("BDMV/index.bdmv", normalizeBdmvRelativePath("BDMV/index.bdmv"))
        assertEquals("BDMV/PLAYLIST/00001.mpls", normalizeBdmvRelativePath("BDMV\\PLAYLIST\\00001.mpls"))
        assertEquals("BDMV/STREAM/00001.m2ts", normalizeBdmvRelativePath("BDMV//STREAM///00001.m2ts"))
        assertEquals("", normalizeBdmvRelativePath(""))
    }

    @Test
    fun traversal_absolute_and_nul_paths_are_rejected_before_the_filesystem() {
        assertNull(normalizeBdmvRelativePath("../outside"))
        assertNull(normalizeBdmvRelativePath("BDMV/../outside"))
        assertNull(normalizeBdmvRelativePath("/storage/emulated/0/movie/BDMV/index.bdmv"))
        assertNull(normalizeBdmvRelativePath("BDMV/./index.bdmv"))
        assertNull(normalizeBdmvRelativePath("BDMV/index.bdmv\u0000ignored"))
    }
}

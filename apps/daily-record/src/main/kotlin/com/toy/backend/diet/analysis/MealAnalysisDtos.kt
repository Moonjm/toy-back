package com.toy.backend.diet.analysis

import com.toy.backend.diet.AnalysisStatus

data class AnalysisCreateRequest(
    val fileIds: List<Long>,
)

data class AnalysisPhotoResponse(
    val fileId: Long,
    val url: String?,
    val failed: Boolean,
    val items: List<AnalyzedItem>,
)

data class AnalysisResponse(
    val id: Long,
    val status: AnalysisStatus,
    val photos: List<AnalysisPhotoResponse>,
)

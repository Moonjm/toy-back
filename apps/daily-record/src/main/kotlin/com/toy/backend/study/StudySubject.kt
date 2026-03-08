package com.toy.backend.study

enum class StudySubject(
    val emoji: String,
    val displayName: String,
) {
    FISCAL("🏛️", "재정학"),
    TAX_LAW_INTRO("📜", "세법학개론"),
    ACCOUNTING_INTRO("📊", "회계학개론"),
    COMMERCIAL_LAW("⚖️", "상법/민법/행정소송법"),
    TAX_LAW_1("📕", "세법학 1부"),
    TAX_LAW_2("📗", "세법학 2부"),
    FINANCIAL_ACCOUNTING("🧮", "회계학 1부"),
    COST_ACCOUNTING("💰", "회계학 2부"),
}

data class StudySubjectResponse(
    val name: String,
    val emoji: String,
    val displayName: String,
)

fun StudySubject.toResponse(): StudySubjectResponse =
    StudySubjectResponse(name = name, emoji = emoji, displayName = displayName)

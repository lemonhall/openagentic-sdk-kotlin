package me.lemonhall.openagentic.sdk.skills

data class SkillDoc(
    val name: String = "",
    val description: String = "",
    val summary: String = "",
    val checklist: List<String> = emptyList(),
    val raw: String = "",
)

data class SkillInfo(
    val name: String,
    val description: String,
    val summary: String,
    val path: String,
)


package me.lemonhall.openagentic.sdk.skills

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

interface Environment {
    fun get(name: String): String?
}

object SystemEnvironment : Environment {
    override fun get(name: String): String? = System.getenv(name)
}

data class MapEnvironment(
    private val data: Map<String, String>,
) : Environment {
    override fun get(name: String): String? = data[name]
}

object OpenAgenticPaths {
    fun defaultAgentsRoot(env: Environment = SystemEnvironment): Path {
        val override = env.get("OPENAGENTIC_AGENTS_HOME")?.trim().orEmpty()
        if (override.isNotEmpty()) return override.toPath()
        val home = System.getProperty("user.home") ?: "."
        return "$home/.agents".toPath()
    }

    fun defaultSessionRoot(env: Environment = SystemEnvironment): Path {
        val override = env.get("OPENAGENTIC_SDK_HOME")?.trim().orEmpty()
        if (override.isNotEmpty()) return override.toPath()
        val home = System.getProperty("user.home") ?: "."
        return "$home/.openagentic-sdk".toPath()
    }
}

private fun iterSkillFiles(
    fileSystem: FileSystem,
    root: Path,
): List<Path> {
    val out = mutableListOf<Path>()
    for (dirname in listOf("skill", "skills")) {
        val d = root.resolve(dirname)
        if (!fileSystem.exists(d)) continue
        for (p in fileSystem.listRecursively(d)) {
            if (p.name == "SKILL.md") out.add(p)
        }
    }
    return out
}

fun indexSkills(
    projectDir: String,
    env: Environment = SystemEnvironment,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): List<SkillInfo> {
    val projectRoot = projectDir.toPath()
    val agentsRoot = OpenAgenticPaths.defaultAgentsRoot(env)
    val globalRoot = OpenAgenticPaths.defaultSessionRoot(env)
    val claudeRoot = projectRoot.resolve(".claude")

    val seen = linkedMapOf<String, SkillInfo>()
    // Precedence: later roots override earlier ones (more local wins).
    for (root in listOf(agentsRoot, globalRoot, projectRoot, claudeRoot)) {
        for (path in iterSkillFiles(fileSystem, root)) {
            val raw =
                fileSystem.read(path) {
                    readUtf8()
                }
            val doc = parseSkillMarkdown(raw)
            val name = if (doc.name.isNotEmpty()) doc.name else path.parent?.name.orEmpty()
            if (name.isEmpty()) continue
            seen[name] = SkillInfo(name = name, description = doc.description, summary = doc.summary, path = path.toString())
        }
    }
    return seen.values.sortedBy { it.name }
}

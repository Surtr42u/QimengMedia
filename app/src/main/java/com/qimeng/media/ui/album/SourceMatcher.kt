package com.qimeng.media.ui.album

import com.qimeng.media.core.AppLog

object SourceMatcher {

    private const val TAG = "SourceMatcher"

    private data class VariantEntry(
        val collapsedKey: String,
        val canonical: String,
        val originalVariant: String
    )

    private data class CharVariantEntry(
        val collapsedKey: String,
        val canonical: String,
        val sourceCanonical: String
    )

    private val BUILTIN_VARIANTS by lazy {
        BUILTIN_GROUPS.flatMap { group ->
            group.variants.map { variant ->
                VariantEntry(
                    collapsedKey = variant.replace("\\s+".toRegex(), "").lowercase(),
                    canonical = group.canonical,
                    originalVariant = variant
                )
            }
        }.sortedByDescending { it.collapsedKey.length }  // 按长度降序排列：最长优先匹配，避免短变体抢先于长变体（如"尼尔"不应抢先于"尼尔机械纪元"）
    }

    private val BUILTIN_CHAR_VARIANTS by lazy {
        BUILTIN_GROUPS.flatMap { group ->
            group.characters.flatMap { char ->
                char.aliases.map { alias ->
                    CharVariantEntry(
                        collapsedKey = alias.replace("\\s+".toRegex(), "").lowercase(),
                        canonical = char.canonical,
                        sourceCanonical = group.canonical
                    )
                }
            }
        }.sortedByDescending { it.collapsedKey.length }  // 按长度降序：最长优先匹配，避免短别名抢先于长别名（如"霞"不应抢先于"霞"的完整别名）
    }

    private val SOURCE_VARIANT_MAP by lazy {
        val map = mutableMapOf<String, List<String>>()
        for (group in BUILTIN_GROUPS) {
            for (variant in group.variants) {
                map.getOrPut(group.canonical) { mutableListOf() }.let {
                    if (variant !in it) (it as MutableList).add(variant)
                }
            }
        }
        map
    }

    private var customSources: Set<String> = emptySet()
    private var customVariants: List<VariantEntry> = emptyList()

    fun updateCustomSources(sources: Set<String>) {
        customSources = sources
        customVariants = sources.map { source ->
            VariantEntry(
                collapsedKey = source.replace("\\s+".toRegex(), "").lowercase(),
                canonical = source,
                originalVariant = source
            )
        }.sortedByDescending { it.collapsedKey.length }
    }

    private var txtWorks: Set<String> = emptySet()
    private var txtWorkVariants: List<VariantEntry> = emptyList()

    fun updateTxtWorks(works: Set<String>) {
        txtWorks = works
        txtWorkVariants = works.map { work ->
            VariantEntry(
                collapsedKey = work.replace("\\s+".toRegex(), "").lowercase(),
                canonical = work,
                originalVariant = work
            )
        }.sortedByDescending { it.collapsedKey.length }
    }

    fun match(fileName: String): String? {
        val nameNoExt = fileName.substringBeforeLast('.')
        val collapsed = nameNoExt.replace("\\s+".toRegex(), "").lowercase()

        // 第一层：内置检测表（collapsed前缀匹配，最精确，优先级最高）
        for (entry in BUILTIN_VARIANTS) {
            if (collapsed.startsWith(entry.collapsedKey)) return entry.canonical
        }
        // 第二层：自定义出处（collapsed前缀匹配）
        for (entry in customVariants) {
            if (collapsed.startsWith(entry.collapsedKey)) return entry.canonical
        }
        // 第三层：txt 作品（collapsed前缀匹配）
        for (entry in txtWorkVariants) {
            if (collapsed.startsWith(entry.collapsedKey)) return entry.canonical
        }

        // 回退：原始文件名前缀匹配（保留空格和大小写，匹配精度较低）
        for (group in BUILTIN_GROUPS) {
            for (variant in group.variants) {
                if (nameNoExt.startsWith(variant, ignoreCase = true)) return group.canonical
            }
        }
        for (source in customSources) {
            if (nameNoExt.startsWith(source, ignoreCase = true)) return source
        }

        return null
    }

    fun matchCharacter(fileName: String, sourceName: String): String? {
        return matchCharacters(fileName, sourceName).ifEmpty { null }?.sorted()?.joinToString("+")
    }

    /**
     * 多角色匹配：先从文件名中去掉已匹配的出处部分，再用剩余部分做角色子串匹配。
     * 例如 "崩坏星穹铁道 飞霄" → 出处"崩坏 星穹铁道" → 去掉出处后"飞霄" → 匹配"飞霄"
     * 这样出处中的"星穹"不会干扰角色匹配，避免"星"被误匹配到"开拓者"。
     */
    fun matchCharacters(fileName: String, sourceName: String): List<String> {
        val nameNoExt = fileName.substringBeforeLast('.')

        // 从文件名中去掉出处部分，用剩余部分做角色匹配
        val remainder = stripSourceFromName(nameNoExt, sourceName)
        val collapsed = remainder.replace("\\s+".toRegex(), "").lowercase()

        // 如果去掉出处后为空，回退到完整文件名
        val searchTarget = if (collapsed.isEmpty()) {
            nameNoExt.replace("\\s+".toRegex(), "").lowercase()
        } else {
            collapsed
        }

        val matched = mutableListOf<String>()
        val matchedRegions = mutableListOf<IntRange>()

        // 第一层：内置角色变体表（全文子串匹配，优先级最高）
        for (entry in BUILTIN_CHAR_VARIANTS) {
            if (entry.sourceCanonical != sourceName) continue
            if (entry.canonical in matched) continue  // 防止同一 canonical 被不同别名重复匹配
            val idx = searchTarget.indexOf(entry.collapsedKey)
            if (idx >= 0) {
                val range = idx until (idx + entry.collapsedKey.length)
                if (matchedRegions.none { it.first < range.last && range.first < it.last }) {
                    matched.add(entry.canonical)
                    matchedRegions.add(range)
                }
            }
        }

        // 第二层：内置角色别名逐个匹配（全文子串匹配）
        for (group in BUILTIN_GROUPS) {
            if (group.canonical != sourceName) continue
            for (char in group.characters) {
                if (char.canonical in matched) continue
                for (alias in char.aliases) {
                    val aliasCollapsed = alias.replace("\\s+".toRegex(), "").lowercase()
                    val idx = searchTarget.indexOf(aliasCollapsed)
                    if (idx >= 0) {
                        val range = idx until (idx + aliasCollapsed.length)
                        if (matchedRegions.none { it.first < range.last && range.first < it.last }) {
                            matched.add(char.canonical)
                            matchedRegions.add(range)
                            break
                        }
                    }
                }
            }
        }

        return matched
    }

    /**
     * 从文件名中去掉出处部分，返回剩余字符串。
     * 按变体长度降序尝试，确保长变体优先匹配。
     * 例如 "崩坏星穹铁道 飞霄" + sourceName="崩坏 星穹铁道" → "飞霄"
     */
    private fun stripSourceFromName(fileName: String, sourceName: String): String {
        val variants = SOURCE_VARIANT_MAP[sourceName] ?: listOf(sourceName)
        val nameCollapsed = fileName.replace("\\s+".toRegex(), "")

        // 按去空格后长度降序排列，长变体优先匹配
        val sortedVariants = variants
            .map { it.replace("\\s+".toRegex(), "") }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }

        for (variantCollapsed in sortedVariants) {
            val idx = nameCollapsed.indexOf(variantCollapsed, ignoreCase = true)
            if (idx == 0) {
                // 出处在文件名开头，去掉出处部分
                val remainder = nameCollapsed.substring(variantCollapsed.length)
                // 先去掉开头空白和标点，再去掉开头数字（但保留后跟字母的，如2B、9S）
                return remainder
                    .replace("^[\\s+_\\-()（）]+".toRegex(), "")
                    .replace("^[\\d]+(?![a-zA-Z])".toRegex(), "")
                    .trim()
            }
        }

        // 如果没有匹配到变体前缀，回退：尝试用 canonical 去掉
        val canonicalCollapsed = sourceName.replace("\\s+".toRegex(), "")
        if (nameCollapsed.startsWith(canonicalCollapsed, ignoreCase = true)) {
            val remainder = nameCollapsed.substring(canonicalCollapsed.length)
            return remainder
                .replace("^[\\s+_\\-()（）]+".toRegex(), "")
                .replace("^[\\d]+(?![a-zA-Z])".toRegex(), "")
                .trim()
        }

        return fileName.trim()
    }

    /**
     * 一次性匹配出处和角色，避免对同一文件名重复调用 match()。
     * 返回 Pair<出处, 角色列表>，出处为 null 表示未匹配到。
     */
    fun matchAll(fileName: String): Pair<String?, List<String>> {
        val sources = matchAllSources(fileName)
        if (sources.isEmpty()) return Pair(null, emptyList())

        // 单出处：直接匹配角色
        if (sources.size == 1) {
            val source = sources[0]
            val characters = matchCharacters(fileName, source)
            if (characters.isEmpty()) {
                val nameNoExt = fileName.substringBeforeLast('.')
                val remainder = stripSourceFromName(nameNoExt, source)
                val searchTarget = remainder.replace("\\s+".toRegex(), "").lowercase()
                AppLog.d(TAG, "charMiss: file='$fileName' source='$source' remainder='$remainder' searchTarget='$searchTarget'")
            }
            return Pair(source, characters)
        }

        // 多出处：合并所有出处的角色匹配结果
        val allCharacters = mutableListOf<String>()
        for (source in sources) {
            allCharacters.addAll(matchCharacters(fileName, source))
        }
        // 去重并排序
        val characters = allCharacters.distinct().sorted()
        val sourceDisplay = sources.sorted().joinToString("+")
        if (characters.isEmpty()) {
            AppLog.d(TAG, "charMiss: file='$fileName' sources='$sourceDisplay'")
        }
        return Pair(sourceDisplay, characters)
    }

    /**
     * 多出处匹配：用 "+" 分割文件名，分别匹配每个部分的出处。
     * 例如 "恶魔战士+铁拳8  莫妮卡+莉莉" → ["恶魔战士", "铁拳"]
     */
    private fun matchAllSources(fileName: String): List<String> {
        val nameNoExt = fileName.substringBeforeLast('.')
        // 如果文件名不含 "+"，直接用单出处匹配
        if (!nameNoExt.contains("+")) {
            return listOfNotNull(match(fileName))
        }

        // 按 "+" 分割，对每个部分尝试出处匹配
        val parts = nameNoExt.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        val sources = mutableListOf<String>()
        for (part in parts) {
            val source = match(part)
            if (source != null && source !in sources) {
                sources.add(source)
            }
        }

        // 如果 "+" 分割后没匹配到任何出处，回退到完整文件名匹配
        if (sources.isEmpty()) {
            return listOfNotNull(match(fileName))
        }

        return sources
    }

    fun allKnownSources(): Set<String> {
        val all = mutableSetOf<String>()
        all.addAll(customSources)
        all.addAll(txtWorks)
        all.addAll(BUILTIN_GROUPS.map { it.canonical })
        return all
    }
}

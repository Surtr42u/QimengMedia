package com.qimeng.media.ui.album

import com.qimeng.media.core.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object SourceMatcher {

    private const val TAG = "SourceMatcher"

    /** charMiss 日志采样间隔：每 50 次未命中记 1 条，避免批量匹配时日志刷屏（GUIDE_DEBUG 日志原则：循环内不加高频日志） */
    private const val CHAR_MISS_LOG_INTERVAL = 50

    /** matchAll 缓存容量上限：超过时清空重建，防长期累积（用户持续扫描新文件场景）。8192 远超常见媒体库规模（数千），极端场景才触发 */
    private const val MATCH_ALL_CACHE_MAX_SIZE = 8192

    /** matchAll 结果缓存：同一 fileName 的出处+角色匹配结果在数据版本内不变，批量遍历（如 groupBySource 对 5816 文件）受益。customSources/txtWorks 更新时清空 */
    private val matchAllCache = ConcurrentHashMap<String, Pair<String?, List<String>>>(256)

    /** charMiss 采样计数器，线程安全 */
    private val charMissCounter = AtomicInteger(0)

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
        // 自定义出处变化，matchAll 缓存失效
        matchAllCache.clear()
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
        // txt 作品变化，matchAll 缓存失效
        matchAllCache.clear()
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
     * 结果按 fileName 缓存（customSources/txtWorks 更新时清空），批量遍历（如 groupBySource 对数千文件）时避免重复匹配。
     */
    fun matchAll(fileName: String): Pair<String?, List<String>> {
        // 缓存命中：同一文件名的出处+角色匹配结果在数据版本内不变
        matchAllCache[fileName]?.let { return it }

        // 容量保护：超过上限时清空重建，防长期累积（用户持续扫描新文件场景）
        if (matchAllCache.size >= MATCH_ALL_CACHE_MAX_SIZE) {
            matchAllCache.clear()
        }

        val sources = matchAllSources(fileName)
        if (sources.isEmpty()) {
            val result = Pair<String?, List<String>>(null, emptyList())
            matchAllCache[fileName] = result
            return result
        }

        // 单出处：直接匹配角色
        if (sources.size == 1) {
            val source = sources[0]
            val characters = matchCharacters(fileName, source)
            if (characters.isEmpty()) {
                logCharMiss("file='$fileName' source='$source'")
            }
            val result = Pair(source, characters)
            matchAllCache[fileName] = result
            return result
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
            logCharMiss("file='$fileName' sources='$sourceDisplay'")
        }
        val result = Pair(sourceDisplay, characters)
        matchAllCache[fileName] = result
        return result
    }

    /**
     * charMiss 日志采样：每 [CHAR_MISS_LOG_INTERVAL] 次记 1 条，避免批量匹配时日志刷屏。
     * 保留诊断能力（未命中总数 = 计数器值），消除 2MB 日志上限过快触发风险。
     */
    private fun logCharMiss(detail: String) {
        val count = charMissCounter.incrementAndGet()
        if (count % CHAR_MISS_LOG_INTERVAL == 1) {
            AppLog.d(TAG, "charMiss($count): $detail")
        }
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

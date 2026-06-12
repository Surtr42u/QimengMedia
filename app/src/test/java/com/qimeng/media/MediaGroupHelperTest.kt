package com.qimeng.media

import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType
import com.qimeng.media.ui.browser.MediaGroupHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGroupHelperTest {

    private fun createMedia(
        fileName: String,
        recordKey: String = fileName,
        mediaType: String = MediaType.IMAGE,
        isCosFile: Boolean = false,
        folderName: String = ""
    ) = MediaFileEntity(
        recordKey = recordKey,
        fileName = fileName,
        displayName = fileName.substringBeforeLast('.'),
        extension = fileName.substringAfterLast('.', ""),
        mediaType = mediaType,
        uriString = "content://test/$fileName",
        folderName = folderName,
        pathHash = "hash_$recordKey",
        sizeBytes = 1024L,
        modifiedAtMillis = System.currentTimeMillis(),
        isCosFile = isCosFile,
        indexedAtMillis = System.currentTimeMillis()
    )

    private fun createAuthorMedia(
        authorId: String,
        recordKey: String,
        fileName: String = "file",
        isMatched: Boolean = true
    ) = AuthorMediaCrossRef(
        authorId = authorId,
        recordKey = recordKey,
        fileName = fileName,
        isMatched = isMatched
    )

    private fun createAuthor(
        authorId: String,
        displayName: String
    ) = AuthorEntity(
        authorId = authorId,
        displayName = displayName,
        createdAtMillis = System.currentTimeMillis(),
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun createCosWork(
        authorName: String,
        workName: String,
        folderUri: String = "content://test/$workName",
        fileCount: Int = 1
    ) = CosWorkEntity(
        authorName = authorName,
        workName = workName,
        folderUri = folderUri,
        fileCount = fileCount,
        indexedAtMillis = System.currentTimeMillis()
    )

    // === groupBySource 测试 ===

    @Test
    fun groupBySource_groupsBySourceMatcher() {
        val media = listOf(
            createMedia("原神 刻晴 1.jpg"),
            createMedia("原神 刻晴 2.jpg"),
            createMedia("崩坏 星穹铁道 卡芙卡 1.mp4")
        )
        val groups = MediaGroupHelper.groupBySource(media)
        assert(groups.size >= 2)
    }

    @Test
    fun groupBySource_unknownFilesGoToOther() {
        val media = listOf(
            createMedia("random_file.jpg"),
            createMedia("another_random.png")
        )
        val groups = MediaGroupHelper.groupBySource(media)
        assert(groups.containsKey("其他"))
        assertEquals(2, groups["其他"]?.size)
    }

    @Test
    fun groupBySource_emptyList() {
        val groups = MediaGroupHelper.groupBySource(emptyList())
        assertEquals(0, groups.size)
    }

    // === groupByCharacter 测试 ===

    @Test
    fun groupByCharacter_groupsByCharacterName() {
        val media = listOf(
            createMedia("原神 刻晴 1.jpg"),
            createMedia("原神 刻晴 2.jpg"),
            createMedia("原神 芭芭拉 1.jpg")
        )
        val groups = MediaGroupHelper.groupByCharacter(media)
        assert(groups.size >= 2)
    }

    @Test
    fun groupByCharacter_noCharacterGoesToOther() {
        val media = listOf(
            createMedia("scenery.jpg")
        )
        val groups = MediaGroupHelper.groupByCharacter(media)
        assert(groups.containsKey("其他"))
    }

    // === groupByCosAuthor 测试 ===

    @Test
    fun groupByCosAuthor_groupsByAuthorName() {
        val media = listOf(
            createMedia("photo1.jpg", recordKey = "rk1", isCosFile = true),
            createMedia("photo2.jpg", recordKey = "rk2", isCosFile = true)
        )
        val authorMedia = listOf(
            createAuthorMedia("cos_author1", "rk1"),
            createAuthorMedia("cos_author1", "rk2")
        )
        val authors = listOf(
            createAuthor("cos_author1", "作者A")
        )
        val groups = MediaGroupHelper.groupByCosAuthor(media, authorMedia, authors)
        assertEquals(1, groups.size)
        assertEquals("作者A", groups.keys.first())
        assertEquals(2, groups["作者A"]?.size)
    }

    @Test
    fun groupByCosAuthor_noMatchGoesToOther() {
        val media = listOf(
            createMedia("photo1.jpg", recordKey = "rk1", isCosFile = true)
        )
        val groups = MediaGroupHelper.groupByCosAuthor(media, emptyList(), emptyList())
        assertEquals(1, groups.size)
        assertEquals("其他", groups.keys.first())
    }

    // === groupByCosWork 测试 ===

    @Test
    fun groupByCosWork_groupsByWorkName() {
        val media = listOf(
            createMedia("photo1.jpg", recordKey = "rk1", isCosFile = true, folderName = "作品1"),
            createMedia("photo2.jpg", recordKey = "rk2", isCosFile = true, folderName = "作品1"),
            createMedia("photo3.jpg", recordKey = "rk3", isCosFile = true, folderName = "作品2")
        )
        val authorMedia = listOf(
            createAuthorMedia("cos_a1", "rk1"),
            createAuthorMedia("cos_a1", "rk2"),
            createAuthorMedia("cos_a1", "rk3")
        )
        val authors = listOf(createAuthor("cos_a1", "作者A"))
        val cosWorks = listOf(
            createCosWork("作者A", "作品1", fileCount = 2),
            createCosWork("作者A", "作品2", fileCount = 1)
        )
        val groups = MediaGroupHelper.groupByCosWork(media, authorMedia, authors, cosWorks)
        assertEquals(2, groups.size)
        assertEquals(2, groups["作品1"]?.size)
        assertEquals(1, groups["作品2"]?.size)
    }

    // === extractCharacterName 测试 ===

    @Test
    fun extractCharacterName_returnsCharacterFromFileName() {
        val media = createMedia("原神 刻晴 1.jpg")
        val charName = MediaGroupHelper.extractCharacterName(media)
        assert(charName.isNotEmpty())
        assert(charName != "其他")
    }

    @Test
    fun extractCharacterName_noMatchReturnsOther() {
        val media = createMedia("random_numbers_123.jpg")
        val charName = MediaGroupHelper.extractCharacterName(media)
        assertEquals("其他", charName)
    }

    // === findCosAuthorForMedia 测试 ===

    @Test
    fun findCosAuthorForMedia_findsAuthor() {
        val media = createMedia("photo.jpg", recordKey = "rk1")
        val authorMedia = listOf(createAuthorMedia("cos_a1", "rk1"))
        val authors = listOf(createAuthor("cos_a1", "作者A"))
        val result = MediaGroupHelper.findCosAuthorForMedia(media, authorMedia, authors)
        assertEquals("作者A", result)
    }

    @Test
    fun findCosAuthorForMedia_noMatchReturnsOther() {
        val media = createMedia("photo.jpg", recordKey = "rk1")
        val result = MediaGroupHelper.findCosAuthorForMedia(media, emptyList(), emptyList())
        assertEquals("其他", result)
    }

    @Test
    fun findCosAuthorForMedia_ignoresNonCosAuthor() {
        val media = createMedia("photo.jpg", recordKey = "rk1")
        val authorMedia = listOf(createAuthorMedia("regular_a1", "rk1"))
        val authors = listOf(createAuthor("regular_a1", "普通作者"))
        val result = MediaGroupHelper.findCosAuthorForMedia(media, authorMedia, authors)
        assertEquals("其他", result)
    }
}

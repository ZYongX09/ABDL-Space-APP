package org.joinmastodon.reader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelDatabaseTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val openedDatabases = mutableListOf<NovelDatabase>()
	private val accountIds = mutableSetOf<String>()

	@After
	fun tearDown() {
		openedDatabases.forEach(NovelDatabase::close)
		accountIds.forEach { context.deleteDatabase(NovelDatabase.databaseName(it)) }
	}

	@Test
	fun sameBookIdIsIsolatedByAccountAndDeletingADoesNotAffectB() = runBlocking {
		val accountA = "mastodon.example_100"
		val accountB = "mastodon.example_200"
		val databaseA = open(accountA)
		val databaseB = open(accountB)
		val sharedBookId = "book:stable-id"

		databaseA.novelBookDao().upsert(NovelBookEntity(sharedBookId, accountA, "Account A book"))
		databaseB.novelBookDao().upsert(NovelBookEntity(sharedBookId, accountB, "Account B book"))

		assertEquals("Account A book", databaseA.novelBookDao().getById(accountA, sharedBookId)?.title)
		assertEquals("Account B book", databaseB.novelBookDao().getById(accountB, sharedBookId)?.title)

		databaseA.novelBookDao().deleteById(accountA, sharedBookId)

		assertNull(databaseA.novelBookDao().getById(accountA, sharedBookId))
		assertEquals("Account B book", databaseB.novelBookDao().getById(accountB, sharedBookId)?.title)
	}

	@Test
	fun bookUsesStableStringPrimaryKey() = runBlocking {
		val database = open("mastodon.example_300")
		val stableId = "https://books.example/novel/42"

		val accountId = "mastodon.example_300"
		database.novelBookDao().upsert(NovelBookEntity(stableId, accountId, "First title"))
		database.novelBookDao().upsert(NovelBookEntity(stableId, accountId, "Updated title"))

		assertEquals(1, database.novelBookDao().count(accountId))
		assertEquals(stableId, database.novelBookDao().getById(accountId, stableId)?.id)
		assertEquals("Updated title", database.novelBookDao().getById(accountId, stableId)?.title)
	}

	@Test
	fun databaseNameUsesAccountHashWithoutLeakingAccountId() {
		val accountId = "social.example_123456789"
		val expectedHash = MessageDigest.getInstance("SHA-256")
			.digest(accountId.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

		val databaseName = NovelDatabase.databaseName(accountId)

		assertEquals("novels_$expectedHash.db", databaseName)
		assertFalse(databaseName.contains(accountId))
	}

	@Test
	fun syncEntitiesExposeRequiredAccountAndLifecycleFields() {
		assertFields(
			NovelBookEntity::class.java,
			"accountId",
			"remoteId",
			"sourceType",
			"contentHash",
			"localFilePath",
			"downloadState",
			"remoteUpdatedAt",
			"deletedAt",
		)
		assertFields(BookmarkEntity::class.java, "accountId", "updatedAt", "deletedAt")
		assertFields(AnnotationEntity::class.java, "accountId", "updatedAt", "deletedAt")
	}

	private fun open(accountId: String): NovelDatabase {
		accountIds += accountId
		return NovelDatabase.open(context, accountId).also(openedDatabases::add)
	}

	private fun assertFields(entityClass: Class<*>, vararg expectedFields: String) {
		val actualFields = entityClass.declaredFields.mapTo(mutableSetOf()) { it.name }
		expectedFields.forEach { field ->
			assertTrue("${entityClass.simpleName} must define $field", field in actualFields)
		}
	}
}

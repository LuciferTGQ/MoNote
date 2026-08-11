package app.monote.mobile.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addButtonShowsAllFourRealActions() {
        showLibrary()

        composeRule.onNodeWithContentDescription("添加").performClick()

        listOf("导入 Markdown", "导入文件夹", "新建笔记", "新建目录").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun longPressingDocumentEntersSelectionAndShowsMoveAndDelete() {
        var state by mutableStateOf(sampleState())
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    state = state,
                    onSelectionChange = { state = state.copy(selectedIds = it) },
                )
            }
        }

        composeRule.onNodeWithText("生物复习.md")
            .performSemanticsAction(SemanticsActions.OnLongClick)

        composeRule.onNodeWithText("移动").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test
    fun searchInputDispatchesQuery() {
        var query = ""
        showLibrary(onQueryChange = { query = it })

        composeRule.onNodeWithTag("library-search").performTextInput("细胞")

        composeRule.runOnIdle { assertEquals("细胞", query) }
    }

    @Test
    fun importSheetConfirmsImportAndOpen() {
        var confirmed = false
        composeRule.setContent {
            MaterialTheme {
                ImportSheet(
                    selectedNames = listOf("../课堂:笔记.md", "复习.md"),
                    targetDirectory = "MoNote/生物",
                    onDismiss = {},
                    onConfirm = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithText("已选择 2 个文件").assertIsDisplayed()
        composeRule.onNodeWithText("安全名称：.._课堂_笔记.md、复习.md").assertIsDisplayed()
        composeRule.onNodeWithText("同名文件将自动加序号").assertIsDisplayed()
        composeRule.onNodeWithText("导入并打开").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun folderImportSheetKeepsFailureVisibleAndPreventsDuplicateConfirmation() {
        composeRule.setContent {
            MaterialTheme {
                ImportSheet(
                    selectedNames = listOf("课程"),
                    targetDirectory = "MoNote",
                    isFolder = true,
                    error = "导入失败：目录过大",
                    confirming = true,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("导入失败：目录过大").assertIsDisplayed()
        composeRule.onNodeWithText("正在导入…").assertIsNotEnabled()
    }

    @Test
    fun selectionActionsMatchTheCapabilitiesOfSelectedItems() {
        val folder = LibraryItem("folder:生物", File("/MoNote/生物"), "生物", "生物", true)
        val note = sampleState().items.single()
        val second = note.copy(id = "chemistry", displayName = "化学.md", relativePath = "化学.md")
        var state by mutableStateOf(sampleState().copy(items = listOf(folder), selectedIds = setOf(folder.id)))
        composeRule.setContent { MaterialTheme { LibraryScreen(state = state) } }

        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        composeRule.onNodeWithText("标签").assertIsDisplayed()
        composeRule.onNodeWithText("导出").assertDoesNotExist()
        composeRule.onNodeWithText("重命名").assertIsDisplayed()
        composeRule.onNodeWithText("移动").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()

        composeRule.runOnIdle { state = sampleState().copy(items = listOf(note, second), selectedIds = setOf(note.id, second.id)) }
        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        composeRule.onNodeWithText("标签").assertIsDisplayed()
        composeRule.onNodeWithText("重命名").assertDoesNotExist()
        composeRule.onNodeWithText("导出").assertDoesNotExist()

        composeRule.runOnIdle { state = sampleState().copy(selectedIds = setOf(note.id)) }
        composeRule.onNodeWithText("重命名").assertIsDisplayed()
        composeRule.onNodeWithText("导出").assertIsDisplayed()
        composeRule.onNodeWithText("导出/分享").assertDoesNotExist()
    }

    @Test
    fun durableMoveRecoveryNoticeExecutesTheSelectedRecord() {
        val recovery = MoveRecoveryRecord(
            original = File("/MoNote/course"),
            current = File("/MoNote/archive/course"),
            message = "rollback denied",
        )
        var recoveredId: String? = null
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    state = sampleState().copy(moveRecoveryRecords = listOf(recovery)),
                    onRecoverMove = { recoveredId = it },
                )
            }
        }

        composeRule.onNodeWithText("已记录待恢复").assertIsDisplayed()
        composeRule.onNodeWithText("执行恢复").performClick()

        composeRule.runOnIdle { assertEquals(recovery.id, recoveredId) }
    }

    @Test
    fun childFolderShowsWorkingNavigateUpAction() {
        var navigateUpCalls = 0
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(
                    state = sampleState().copy(canNavigateUp = true),
                    onNavigateUp = { navigateUpCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("library-navigate-up").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, navigateUpCalls) }
    }

    @Test
    fun scanWarningsAreVisibleWithoutReplacingLibraryContents() {
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(state = sampleState().copy(scanWarnings = listOf("unreadable-note.md")))
            }
        }

        composeRule.onNodeWithTag("library-scan-warnings").assertIsDisplayed()
        composeRule.onNodeWithText("unreadable-note.md").assertIsDisplayed()
        composeRule.onNodeWithText(sampleState().items.single().displayName).assertIsDisplayed()
    }

    @Test
    fun storageShowsMeasuredCategoriesWithoutPlaceholderText() {
        composeRule.setContent {
            MaterialTheme {
                StorageScreen(
                    breakdown = StorageBreakdown(1, 2, 3, 4, attachmentsBytes = 5, backupsBytes = 6),
                    onOpenRoot = {},
                    onClear = {},
                )
            }
        }

        listOf("正文", "附件", "回收站", "恢复草稿", "备份", "渲染缓存").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("由后续服务管理").assertDoesNotExist()
    }

    @Test
    fun storageFailureIsVisible() {
        composeRule.setContent {
            MaterialTheme {
                StorageScreen(
                    breakdown = null,
                    onOpenRoot = {},
                    onClear = {},
                    error = "storage unavailable",
                )
            }
        }

        composeRule.onNodeWithText("storage unavailable").assertIsDisplayed()
    }

    @Test
    fun permanentDeleteAndEmptyTrashRequireSecondConfirmation() {
        var permanentlyDeleted = false
        var emptied = false
        composeRule.setContent {
            MaterialTheme {
                TrashScreen(
                    entries = listOf(
                        TrashEntry(
                            stableId = "trash-1",
                            originalRelativePath = "生物复习.md",
                            deletedAt = java.time.Instant.EPOCH,
                            trashedFile = File("trash/content.md"),
                            metadataFile = File("trash/entry.json"),
                        ),
                    ),
                    onRestore = {},
                    onDeletePermanently = { permanentlyDeleted = true },
                    onEmptyTrash = { emptied = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("永久删除 生物复习.md").performClick()
        composeRule.onNodeWithText("永久删除后无法恢复").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!permanentlyDeleted) }
        composeRule.onNodeWithText("确认永久删除").performClick()
        composeRule.runOnIdle { assertTrue(permanentlyDeleted) }

        composeRule.onNodeWithText("清空回收站").performClick()
        composeRule.onNodeWithText("确认清空回收站").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!emptied) }
        composeRule.onNodeWithText("确认清空").performClick()
        composeRule.runOnIdle { assertTrue(emptied) }
    }

    private fun showLibrary(state: LibraryUiState = sampleState(), onQueryChange: (String) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                LibraryScreen(state = state, onQueryChange = onQueryChange)
            }
        }
    }

    private fun sampleState() = LibraryUiState(
        currentFolder = File("/MoNote"),
        recent = emptyList(),
        items = listOf(
            LibraryItem(
                id = "biology",
                file = File("/MoNote/生物复习.md"),
                relativePath = "生物复习.md",
                displayName = "生物复习.md",
                isFolder = false,
            ),
        ),
    )
}

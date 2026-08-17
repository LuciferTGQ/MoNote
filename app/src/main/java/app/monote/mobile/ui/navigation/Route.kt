package app.monote.mobile.ui.navigation

enum class Route(
    val path: String,
    val title: String,
    val mark: String,
) {
    Library("library", "资料库", "库"),
    Editor("editor", "编辑器", "写"),
    Trash("trash", "回收站", "弃"),
    Storage("storage", "存储", "盘"),
    Settings("settings", "设置", "设"),
}

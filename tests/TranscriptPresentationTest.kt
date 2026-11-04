package org.hotwordtest

fun main() {
    val first = "[00:03.49–00:06.27]说话人2（本窗口）：测试测试"
    val second = "[00:07–00:08]说话人7（本文件）：我说了说话人2（本窗口）"
    val repeat = "[00:09–00:10]说话人2：同一个人"
    val overlap = "[00:10–00:12]重叠：说话人7、说话人2：同时说话"
    val mixed = "[00:12–00:14]重叠：说话人7、另有未归组声音：好"
    val named = "[00:14–00:16]小李：我读到的词是说话人7（本文件）"
    val unknown = "[00:16–00:18]说话人未识别：嗯"
    val lines = listOf(first, second, repeat, overlap, mixed, named, unknown)
    val expected = listOf(
        "[00:03.49–00:06.27]说话人1：测试测试",
        "[00:07–00:08]说话人2：我说了说话人2（本窗口）",
        "[00:09–00:10]说话人1：同一个人",
        "[00:10–00:12]重叠：说话人2、说话人1：同时说话",
        "[00:12–00:14]重叠：说话人2、另有未归组声音：好",
        named,
        unknown
    )
    val shown = TranscriptPresentation.render(lines.joinToString("\n"))
    check(shown == expected.joinToString("\n")) { shown }
    check(TranscriptPresentation.render(shown) == shown)
    val sequential = TranscriptPresentation.Names()
    check(lines.map(sequential::line) == expected)
    check(TranscriptPresentation.render(second).startsWith("[00:07–00:08]说话人1："))

    println("PASS first-visible numbering, repeat, mixed overlap, named and unknown speakers, preserved speech, new session")
}

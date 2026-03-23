package io.github.takahino.reporter.controller

private[controller] object HtmlUtil {

  def escHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
     .replace("\"", "&quot;").replace("'", "&#39;")

  def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
     .replace("\n", "\\n").replace("\r", "\\r")
     .replace("\t", "\\t")

  def checked(b: Boolean): String = if (b) "checked" else ""

  def alertHtml(message: Option[String]): String = message.map { m =>
    val alertClass = if (m.startsWith("送信エラー")) "alert-danger" else "alert-info"
    s"""<div class="alert $alertClass">${escHtml(m)}</div>"""
  }.getOrElse("")

  val dayLabels: Seq[(String, String)] = Seq(
    "1" -> "月", "2" -> "火", "3" -> "水",
    "4" -> "木", "5" -> "金", "6" -> "土", "7" -> "日"
  )

  def dayCheckboxes(selected: Set[String]): String =
    dayLabels.map { case (value, label) =>
      val checked = if (selected.contains(value)) "checked" else ""
      s"""<label style="display:inline-block;margin-right:10px;font-weight:normal;">
         |  <input type="checkbox" name="daysOfWeek" value="$value" $checked> $label
         |</label>""".stripMargin
    }.mkString("\n")

  def hourOptions(selected: Int): String =
    (0 to 23).map { h =>
      val sel = if (h == selected) "selected" else ""
      s"""<option value="$h" $sel>${"%02d".format(h)}</option>"""
    }.mkString

  def minuteOptions(selected: Int): String =
    (0 to 59).map { m =>
      val sel = if (m == selected) "selected" else ""
      s"""<option value="$m" $sel>${"%02d".format(m)}</option>"""
    }.mkString

  /**
   * GitBucket のナビバー・CSS を含む完全なHTMLページを生成する。
   *
   * @param title       ブラウザタブのタイトル
   * @param owner       リポジトリオーナー名
   * @param repo        リポジトリ名
   * @param pageIcon    ページタイトル横の octicon クラス名（例: "mail"）
   * @param pageTitle   ページ見出し
   * @param content     <body> 内のメインコンテンツ HTML
   * @param extraScript 追加の <script> ブロック内コード（省略可）
   */
  def pageShell(
    title:       String,
    owner:       String,
    repo:        String,
    pageIcon:    String,
    pageTitle:   String,
    content:     String,
    extraScript: String  = "",
    wideLayout:  Boolean = false,
    contextPath: String  = ""
  ): String = {
    val ownerE   = escHtml(owner)
    val repoE    = escHtml(repo)
    val titleE   = escHtml(title)
    val colClass = if (wideLayout) "col-md-12" else "col-md-8 col-md-offset-2"
    val ctxE     = escHtml(contextPath)

    s"""<!DOCTYPE html>
       |<html lang="ja">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1">
       |  <title>$titleE</title>
       |  <link rel="stylesheet" href="$ctxE/assets/vendors/bootstrap-3.4.1/css/bootstrap.min.css">
       |  <link rel="stylesheet" href="$ctxE/assets/vendors/octicons-4.4.0/octicons.css">
       |  <link rel="stylesheet" href="$ctxE/assets/common/css/gitbucket.css">
       |</head>
       |<body>
       |
       |<div class="container-fluid" style="margin-top:24px;">
       |  <div class="row">
       |    <div class="$colClass">
       |
       |      <h3 style="margin-top:0;margin-bottom:16px;">
       |        <i class="octicon octicon-$pageIcon"></i> $pageTitle
       |      </h3>
       |
       |      $content
       |
       |    </div>
       |  </div>
       |</div>
       |
       |<script src="$ctxE/assets/vendors/jquery/jquery-3.5.1.min.js"></script>
       |<script src="$ctxE/assets/vendors/bootstrap-3.4.1/js/bootstrap.min.js"></script>
       |${if (extraScript.nonEmpty) s"<script>\n$extraScript\n</script>" else ""}
       |</body></html>""".stripMargin
  }
}

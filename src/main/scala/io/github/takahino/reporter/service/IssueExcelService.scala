package io.github.takahino.reporter.service

import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xddf.usermodel.chart._
import org.apache.poi.xddf.usermodel.{XDDFColorRgbBinary, XDDFLineProperties, XDDFShapeProperties, XDDFSolidFillProperties}
import org.apache.poi.xssf.usermodel.{XSSFSheet, XSSFWorkbook}

import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.format.DateTimeFormatter
import scala.collection.mutable

object IssueReportService {

  val MaxCellLength    = 32767
  val MaxBodyColWidth  = 80 * 256  // 80文字幅相当（1/256文字単位）
  /** POI 列幅の上限（255文字相当、単位は 1/256 文字） */
  private val MaxColumnWidthUnits = 255 * 256
  private val milestoneDueDateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")

  case class ColumnDef(
    key:       String,
    header:    String,
    extract:   IssueRow => String,
    isNumeric: Boolean = false,
    isBody:    Boolean = false,
    isTitle:   Boolean = false
  )

  val AllColumns: Seq[ColumnDef] = Seq(
    ColumnDef("issue_id",             "Issue#",            r => r.issueId.toString,                        isNumeric = true),
    ColumnDef("title",                "タイトル",           r => r.title,                                   isTitle   = true),
    ColumnDef("body",                 "本文",               r => r.body.take(MaxCellLength),                isBody    = true),
    ColumnDef("status",               "状態",               r => if (r.closed) "closed" else "open"),
    ColumnDef("creator",              "作成者",             r => r.creator),
    ColumnDef("assignee",             "担当者",             r => r.assignee),
    ColumnDef("labels",               "ラベル",             r => r.labels),
    ColumnDef("milestone",            "マイルストーン",      r => r.milestone),
    ColumnDef("comment_count",        "コメント数",          r => r.commentCount.toString,                   isNumeric = true),
    ColumnDef("created_at",           "作成日時",           r => r.createdAt),
    ColumnDef("updated_at",           "更新日時",           r => r.updatedAt),
    ColumnDef("closed_date",          "クローズ日",          r => r.closedDate),
    ColumnDef("url",                  "URL",                r => r.url),
    ColumnDef("note",                 "備考",               r => r.note),
    ColumnDef("waiting_confirmation", "確認待ち",           r => if (r.waitingForConfirmation) "○" else ""),
    ColumnDef("confirmation_detail",  "確認詳細",           r => r.confirmationDetail),
    ColumnDef("milestone_due_date",   "マイルストーン期日",   r => r.milestoneDueDate),
    ColumnDef("start_date",           "開始予定日",          r => r.startDate.getOrElse("")),
    ColumnDef("end_date",             "完了予定日",          r => r.endDate.getOrElse("")),
    ColumnDef("progress",             "進捗(%)",            r => r.progress.map(_.toString).getOrElse(""),  isNumeric = true),
    ColumnDef("estimated_hours",      "見積工数(h)",         r => r.estimatedHours.map(_.toString).getOrElse(""), isNumeric = true),
    ColumnDef("actual_hours",         "実績工数(h)",         r => r.actualHours.map(_.toString).getOrElse(""),    isNumeric = true)
  )

  val ColumnDefByKey: Map[String, ColumnDef] = AllColumns.map(c => c.key -> c).toMap
  val DefaultColumnOrder: Seq[String]        = AllColumns.map(_.key)

  def resolveColumns(columnOrder: String): Seq[ColumnDef] = {
    val trimmed = columnOrder.trim
    if (trimmed.isEmpty) AllColumns
    else trimmed.split(",").map(_.trim).filter(_.nonEmpty)
                .flatMap(ColumnDefByKey.get).toSeq
  }

  /**
   * 列幅推定用: 半角は 1、全角相当（日本語など）は 2 としてざっくり幅単位を数える。
   * POI の autoSizeColumn は環境・フォントによって日本語がかなり狭く見積もられることがある。
   */
  private def displayWidthUnits(s: String): Int =
    if (s == null || s.isEmpty) 0
    else
      s.foldLeft(0) { (n, ch) =>
        n + (if (ch <= 0x007f) 1 else 2)
      }

  case class IssueRow(
    issueId:      Int,
    title:        String,
    closed:       Boolean,
    assignee:     String,
    milestone:    String,
    labels:       String,
    createdAt:    String,
    updatedAt:    String,
    milestoneDueDate: String,
    closedDate:   String,
    creator:      String,
    body:         String,
    commentCount: Int,
    url:                    String,
    note:                   String   = "",
    waitingForConfirmation: Boolean  = false,
    confirmationDetail:     String   = "",
    milestoneId:            Int      = 0,
    labelIds:               Seq[Int] = Nil,
    startDate:              Option[String] = None,
    endDate:                Option[String] = None,
    progress:               Option[Int]    = None,
    estimatedHours:         Option[Double] = None,
    actualHours:            Option[Double] = None
  )

  /**
   * ISSUE テーブルから全 issue（open + closed、PR 除く）を取得する。
   * ラベルはサブクエリで comma 区切り文字列として結合する。
   */
  def loadIssues(conn: Connection, owner: String, repository: String, baseUrl: String): Seq[IssueRow] = {
    val sql =
      """SELECT
        |  i.ISSUE_ID,
        |  i.TITLE,
        |  i.CLOSED,
        |  COALESCE(
        |    (SELECT ia.ASSIGNEE_USER_NAME
        |     FROM ISSUE_ASSIGNEE ia
        |     WHERE ia.USER_NAME = i.USER_NAME
        |       AND ia.REPOSITORY_NAME = i.REPOSITORY_NAME
        |       AND ia.ISSUE_ID = i.ISSUE_ID
        |     LIMIT 1), '') AS ASSIGNEE,
        |  COALESCE(m.TITLE, '') AS MILESTONE,
        |  COALESCE(m.MILESTONE_ID, 0) AS MILESTONE_ID,
        |  FORMATDATETIME(i.REGISTERED_DATE, 'yyyy/MM/dd HH:mm') AS CREATED_AT,
        |  FORMATDATETIME(i.UPDATED_DATE,    'yyyy/MM/dd HH:mm') AS UPDATED_AT,
        |  m.DUE_DATE AS MILESTONE_DUE_DATE,
        |  CASE WHEN i.CLOSED = TRUE
        |    THEN FORMATDATETIME(i.UPDATED_DATE, 'yyyy/MM/dd HH:mm')
        |    ELSE ''
        |  END AS CLOSED_DATE,
        |  i.OPENED_USER_NAME AS CREATOR,
        |  COALESCE(i.CONTENT, '') AS BODY,
        |  (SELECT COUNT(*)
        |   FROM ISSUE_COMMENT ic
        |   WHERE ic.USER_NAME = i.USER_NAME
        |     AND ic.REPOSITORY_NAME = i.REPOSITORY_NAME
        |     AND ic.ISSUE_ID = i.ISSUE_ID
        |     AND ic.ACTION IN ('comment', 'close_comment', 'reopen_comment')) AS COMMENT_COUNT
        |FROM ISSUE i
        |LEFT JOIN MILESTONE m ON m.MILESTONE_ID = i.MILESTONE_ID
        |  AND m.USER_NAME = i.USER_NAME
        |  AND m.REPOSITORY_NAME = i.REPOSITORY_NAME
        |WHERE i.USER_NAME = ?
        |  AND i.REPOSITORY_NAME = ?
        |  AND i.PULL_REQUEST = FALSE
        |ORDER BY i.ISSUE_ID""".stripMargin

    val labelSql =
      """SELECT il.ISSUE_ID, l.LABEL_ID, l.LABEL_NAME
        |FROM ISSUE_LABEL il
        |INNER JOIN LABEL l ON l.LABEL_ID = il.LABEL_ID
        |  AND l.USER_NAME = ?
        |  AND l.REPOSITORY_NAME = ?
        |WHERE il.USER_NAME = ?
        |  AND il.REPOSITORY_NAME = ?""".stripMargin

    // ラベルマップを先に構築（名前とIDの両方）
    val labelMap   = mutable.Map.empty[Int, mutable.Buffer[String]]
    val labelIdMap = mutable.Map.empty[Int, mutable.Buffer[Int]]
    val lps = conn.prepareStatement(labelSql)
    lps.setString(1, owner); lps.setString(2, repository)
    lps.setString(3, owner); lps.setString(4, repository)
    val lrs = lps.executeQuery()
    while (lrs.next()) {
      val id      = lrs.getInt("ISSUE_ID")
      val labelId = lrs.getInt("LABEL_ID")
      val name    = lrs.getString("LABEL_NAME")
      labelMap.getOrElseUpdate(id, mutable.Buffer.empty)   += name
      labelIdMap.getOrElseUpdate(id, mutable.Buffer.empty) += labelId
    }
    lrs.close(); lps.close()

    // Issue 本体
    val ps  = conn.prepareStatement(sql)
    ps.setString(1, owner); ps.setString(2, repository)
    val rs  = ps.executeQuery()
    val buf = mutable.Buffer.empty[IssueRow]
    while (rs.next()) {
      val id = rs.getInt("ISSUE_ID")
      val milestoneDueDate: String = {
        val d = rs.getDate("MILESTONE_DUE_DATE")
        if (d == null) "" else d.toLocalDate.format(milestoneDueDateFmt)
      }
      buf += IssueRow(
        issueId      = id,
        title        = rs.getString("TITLE"),
        closed       = rs.getBoolean("CLOSED"),
        assignee     = rs.getString("ASSIGNEE"),
        milestone    = rs.getString("MILESTONE"),
        labels       = labelMap.getOrElse(id, Nil).mkString(", "),
        createdAt    = rs.getString("CREATED_AT"),
        updatedAt    = rs.getString("UPDATED_AT"),
        milestoneDueDate = milestoneDueDate,
        closedDate   = rs.getString("CLOSED_DATE"),
        creator      = rs.getString("CREATOR"),
        body         = rs.getString("BODY"),
        commentCount = rs.getInt("COMMENT_COUNT"),
        url          = s"$baseUrl/$owner/$repository/issues/$id",
        milestoneId  = rs.getInt("MILESTONE_ID"),
        labelIds     = labelIdMap.getOrElse(id, Nil).toSeq
      )
    }
    rs.close(); ps.close()
    buf.toSeq
  }

  /**
   * Issue 一覧から xlsx を生成して OutputStream に書き出す。
   * columnOrder が空なら全20列デフォルト順、カンマ区切りキーを指定すると該当列のみ指定順で出力。
   * gantt 列は事前に mergeWithPeriods で IssueRow にマージしておく。
   */
  def generateExcel(
    issues:      Seq[IssueRow],
    out:         OutputStream,
    columnOrder: String = ""
  ): Unit = {
    val cols  = resolveColumns(columnOrder)
    val wb    = new XSSFWorkbook()
    val sheet = wb.createSheet("Issues")

    val creationHelper = wb.getCreationHelper

    val headerStyle = {
      val s = wb.createCellStyle()
      val f = wb.createFont()
      f.setBold(true)
      s.setFont(f)
      s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex)
      s.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      s.setBorderBottom(BorderStyle.THIN)
      s
    }

    val hyperlinkStyle = {
      // タイトル列のハイパーリンク用（Excelでクリック可能に見えるように青+下線）
      val s = wb.createCellStyle()
      val f = wb.createFont()
      f.setUnderline(FontUnderline.SINGLE)
      f.setColor(IndexedColors.BLUE.getIndex)
      s.setFont(f)
      s
    }

    val headerRow = sheet.createRow(0)
    cols.zipWithIndex.foreach { case (col, i) =>
      val cell = headerRow.createCell(i)
      cell.setCellValue(col.header)
      cell.setCellStyle(headerStyle)
    }
    // ヘッダ行に対してフィルタをデフォルトで有効化（列が1つ以上ある場合のみ）
    if (cols.nonEmpty)
      sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, cols.size - 1))

    issues.zipWithIndex.foreach { case (issue, idx) =>
      val dataRow = sheet.createRow(idx + 1)
      cols.zipWithIndex.foreach { case (col, i) =>
        if (col.isNumeric) {
          val v = col.extract(issue)
          if (v.nonEmpty) dataRow.createCell(i).setCellValue(v.toDouble)
          else dataRow.createCell(i).setCellValue("")
        } else if (col.isTitle) {
          val cell = dataRow.createCell(i)
          cell.setCellValue(col.extract(issue))
          if (issue.url != null && issue.url.nonEmpty) {
            val link = creationHelper.createHyperlink(HyperlinkType.URL)
            link.setAddress(issue.url)
            cell.setHyperlink(link)
            cell.setCellStyle(hyperlinkStyle)
          }
        } else {
          dataRow.createCell(i).setCellValue(col.extract(issue))
        }
      }
    }

    // 列幅: autoSizeColumn をベースにしつつ、表示文字列から下限幅を取り max で補正する
    val dataFormatter = new DataFormatter()
    val lastDataRow   = sheet.getLastRowNum

    cols.indices.foreach { col =>
      sheet.autoSizeColumn(col, true)
    }

    cols.zipWithIndex.foreach { case (colDef, col) =>
      var maxUnits = math.ceil(displayWidthUnits(colDef.header) * 1.2).toInt
      var r        = 1
      while (r <= lastDataRow) {
        val dataRow = sheet.getRow(r)
        if (dataRow != null) {
          val cell = dataRow.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
          if (cell != null) {
            val shown = dataFormatter.formatCellValue(cell)
            maxUnits = math.max(maxUnits, displayWidthUnits(shown))
          }
        }
        r += 1
      }
      val contentFloor = math.min(MaxColumnWidthUnits, (maxUnits + 5) * 256)
      val merged       = math.min(MaxColumnWidthUnits, math.max(sheet.getColumnWidth(col), contentFloor))
      sheet.setColumnWidth(col, merged)

      // 本文列は横長になりすぎないよう最大幅を制限する
      if (colDef.isBody && sheet.getColumnWidth(col) > MaxBodyColWidth)
        sheet.setColumnWidth(col, MaxBodyColWidth)
    }

    sheet.createFreezePane(0, 1)

    addBurnupSheet(wb, issues)

    wb.write(out)
    wb.close()
  }

  /** issues に notes の情報をマージして返す */
  def mergeWithNotes(issues: Seq[IssueRow], notes: Map[Int, IssueNote]): Seq[IssueRow] =
    issues.map { i =>
      val n = notes.get(i.issueId)
      i.copy(
        note                   = n.map(_.note).getOrElse(""),
        waitingForConfirmation = n.exists(_.waitingForConfirmation),
        confirmationDetail     = n.map(_.confirmationDetail).getOrElse("")
      )
    }

  /** issues に期間・進捗の情報をマージして返す */
  def mergeWithPeriods(issues: Seq[IssueRow], periods: Map[Int, IssuePeriod]): Seq[IssueRow] =
    issues.map { i =>
      val p = periods.get(i.issueId)
      i.copy(
        startDate      = p.flatMap(_.startDate),
        endDate        = p.flatMap(_.endDate),
        progress       = p.flatMap(_.progress),
        estimatedHours = p.flatMap(_.estimatedHours),
        actualHours    = p.flatMap(_.actualHours)
      )
    }

  /**
   * バーンアップチャートシート（"バーンアップ"）をワークブックに追加する。
   * issues から登録日・完了日・完了予定日を集計し、日付ごとの累計データ表と
   * 折れ線グラフを作成する。
   * defaultDays: 完了予定日が未設定のオープン Issue に適用する仮期限（今日から何日後）
   */
  private def addBurnupSheet(wb: XSSFWorkbook, issues: Seq[IssueRow], defaultDays: Int = 7): Unit = {
    if (issues.isEmpty) return

    val fmtFull = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    val fmtDay  = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

    def parseDay(s: String): Option[java.time.LocalDate] = {
      if (s == null || s.isEmpty) None
      else scala.util.Try(java.time.LocalDate.parse(s.take(16), fmtFull))
             .orElse(scala.util.Try(java.time.LocalDate.parse(s.take(10), fmtDay)))
             .toOption
    }

    val today       = java.time.LocalDate.now()
    val fallbackEnd = today.plusDays(defaultDays)

    // (registeredDate, closeDateOpt, closed, endDateOpt, progressOpt, startDateOpt)
    val parsed = issues.flatMap { row =>
      parseDay(row.createdAt).map { regDate =>
        val closeDateOpt = if (row.closed) parseDay(row.closedDate) else None
        val endDateOpt   = row.endDate
          .flatMap(s => scala.util.Try(java.time.LocalDate.parse(s.take(10), fmtDay)).toOption)
        val startDateOpt = row.startDate
          .flatMap(s => scala.util.Try(java.time.LocalDate.parse(s.take(10), fmtDay)).toOption)
        (regDate, closeDateOpt, row.closed, endDateOpt, row.progress, startDateOpt)
      }
    }

    if (parsed.isEmpty) return

    val startDate    = parsed.map(_._1).minBy(_.toEpochDay)
    val maxEndDate   = (parsed.flatMap(_._4) :+ fallbackEnd).maxBy(_.toEpochDay)
    val effectiveEnd = if (maxEndDate.isBefore(startDate)) startDate else maxEndDate
    val totalDays    = java.time.temporal.ChronoUnit.DAYS.between(startDate, effectiveEnd).toInt
    val days         = (0L to totalDays).map(startDate.plusDays)

    val totalCounts = days.map { d =>
      parsed.count { case (reg, _, _, _, _, _) => !reg.isAfter(d) }
    }
    val completedCounts = days.map { d =>
      parsed.count { case (_, closeDateOpt, closed, _, _, _) =>
        closed && closeDateOpt.exists(!_.isAfter(d))
      }
    }
    val plannedCompletedCounts = days.map { d =>
      parsed.count { case (reg, closeDateOpt, closed, endDateOpt, _, _) =>
        val plannedEnd = endDateOpt.getOrElse(
          if (closed) closeDateOpt.getOrElse(fallbackEnd) else fallbackEnd)
        !reg.isAfter(d) && !plannedEnd.isAfter(d)
      }
    }
    val notStartedCounts = days.map { d =>
      parsed.count { case (reg, closeDateOpt, closed, _, progressOpt, _) =>
        if (reg.isAfter(d)) false
        else {
          val closedByD = closed && closeDateOpt.exists(!_.isAfter(d))
          !closedByD && progressOpt.getOrElse(0) <= 0
        }
      }
    }
    val overdueNotStartedCounts = days.map { d =>
      parsed.count { case (reg, closeDateOpt, closed, _, progressOpt, startDateOpt) =>
        if (reg.isAfter(d)) false
        else {
          val closedByD = closed && closeDateOpt.exists(!_.isAfter(d))
          !closedByD && progressOpt.getOrElse(0) <= 0 && startDateOpt.exists(!_.isAfter(d))
        }
      }
    }
    val inProgressCounts = days.map { d =>
      parsed.count { case (reg, closeDateOpt, closed, _, progressOpt, _) =>
        if (reg.isAfter(d)) false
        else {
          val closedByD = closed && closeDateOpt.exists(!_.isAfter(d))
          closedByD || (!closedByD && progressOpt.getOrElse(0) >= 1)
        }
      }
    }

    val sheet: XSSFSheet = wb.createSheet("バーンアップ")

    val headerStyle = {
      val s = wb.createCellStyle()
      val f = wb.createFont()
      f.setBold(true)
      s.setFont(f)
      s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex)
      s.setFillPattern(FillPatternType.SOLID_FOREGROUND)
      s.setBorderBottom(BorderStyle.THIN)
      s
    }

    val headers = Seq("日付", "登録数（累計）", "計画完了数（累計）", "未着手（件数）", "開始遅延未着手", "着手（件数）", "完了数（累計）")
    val hRow    = sheet.createRow(0)
    headers.zipWithIndex.foreach { case (h, i) =>
      val cell = hRow.createCell(i)
      cell.setCellValue(h)
      cell.setCellStyle(headerStyle)
    }

    days.zipWithIndex.foreach { case (d, i) =>
      val row = sheet.createRow(i + 1)
      row.createCell(0).setCellValue(d.format(fmtDay))
      row.createCell(1).setCellValue(totalCounts(i).toDouble)
      row.createCell(2).setCellValue(plannedCompletedCounts(i).toDouble)
      row.createCell(3).setCellValue(notStartedCounts(i).toDouble)
      row.createCell(4).setCellValue(overdueNotStartedCounts(i).toDouble)
      row.createCell(5).setCellValue(inProgressCounts(i).toDouble)
      row.createCell(6).setCellValue(completedCounts(i).toDouble)
    }

    sheet.setColumnWidth(0, 12 * 256)
    sheet.setColumnWidth(1, 18 * 256)
    sheet.setColumnWidth(2, 22 * 256)
    sheet.setColumnWidth(3, 16 * 256)
    sheet.setColumnWidth(4, 18 * 256)
    sheet.setColumnWidth(5, 16 * 256)
    sheet.setColumnWidth(6, 18 * 256)
    sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, 6))
    sheet.createFreezePane(0, 1)

    // グラフはデータが2点以上ある場合のみ作成
    if (days.size >= 2) {
      val numRows = days.size
      val drawing = sheet.createDrawingPatriarch()
      val anchor  = wb.getCreationHelper.createClientAnchor()
      anchor.setCol1(8); anchor.setRow1(1)
      anchor.setCol2(18); anchor.setRow2(30)

      val chart  = drawing.createChart(anchor)
      val legend = chart.getOrAddLegend()
      legend.setPosition(LegendPosition.BOTTOM)

      val catAxis = chart.createCategoryAxis(AxisPosition.BOTTOM)
      val valAxis = chart.createValueAxis(AxisPosition.LEFT)
      valAxis.setCrosses(AxisCrosses.AUTO_ZERO)

      val catSource = XDDFDataSourcesFactory.fromStringCellRange(
        sheet, new CellRangeAddress(1, numRows, 0, 0))

      val lineData = chart.createData(ChartTypes.LINE, catAxis, valAxis)
        .asInstanceOf[XDDFLineChartData]

      // (列インデックス, 系列名, RGB色) — Webチャートと同じ色分け
      val seriesDefs = Seq(
        (1, "登録数（累計）",      (0x96, 0x96, 0x96)),  // グレー
        (2, "計画完了数（累計）",   (0xFF, 0x63, 0x84)),  // 赤
        (3, "未着手（件数）",      (0xFF, 0x9F, 0x40)),  // オレンジ
        (4, "開始遅延未着手",      (0xDC, 0x26, 0x26)),  // 深紅
        (5, "着手（件数）",        (0x99, 0x66, 0xFF)),  // 紫
        (6, "完了数（累計）",      (0x36, 0xA2, 0xEB))   // 青
      )

      seriesDefs.foreach { case (colIdx, title, (r, g, b)) =>
        val numSource = XDDFDataSourcesFactory.fromNumericCellRange(
          sheet, new CellRangeAddress(1, numRows, colIdx, colIdx))
        val s = lineData.addSeries(catSource, numSource)
        s.setTitle(title, null)

        // 線の色を設定（XDDFShapeProperties 経由）
        val color     = new XDDFColorRgbBinary(Array(r.toByte, g.toByte, b.toByte))
        val solidFill = new XDDFSolidFillProperties(color)
        val lineProp  = new XDDFLineProperties()
        lineProp.setFillProperties(solidFill)
        val shapeProp = new XDDFShapeProperties()
        shapeProp.setLineProperties(lineProp)
        s.setShapeProperties(shapeProp)

        // マーカーを非表示にして線のみ表示（setMarkerStyle はサブクラスのため反射利用）
        try {
          s.getClass.getMethod("setMarkerStyle", classOf[MarkerStyle])
            .invoke(s, MarkerStyle.NONE)
        } catch { case _: Exception => () }
      }

      chart.plot(lineData)
    }
  }

  /** ファイル名を RFC 5987 形式にエンコードする */
  def encodeFilename(filename: String): String =
    URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

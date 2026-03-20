package io.github.takahino.reporter.service

import io.github.takahino.reporter.model.IssuePeriod
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

class IssueReportServiceSpec extends AnyFunSuite with Matchers {

  // -------------------------------------------------------------------------
  // encodeFilename
  // -------------------------------------------------------------------------

  test("encodeFilename: ASCII文字はそのまま") {
    IssueReportService.encodeFilename("report.xlsx") shouldBe "report.xlsx"
  }

  test("encodeFilename: スペースは%20にエンコード（+は使わない）") {
    val encoded = IssueReportService.encodeFilename("my report.xlsx")
    encoded shouldBe "my%20report.xlsx"
    encoded should not contain "+"
  }

  test("encodeFilename: 日本語はURLエンコードされ+を含まない") {
    val encoded = IssueReportService.encodeFilename("課題一覧.xlsx")
    encoded should not be "課題一覧.xlsx"
    encoded should not contain "+"
    encoded should startWith("%")
  }

  test("encodeFilename: アンダースコアはそのまま") {
    IssueReportService.encodeFilename("issues_export.xlsx") shouldBe "issues_export.xlsx"
  }

  // -------------------------------------------------------------------------
  // generateExcel
  // -------------------------------------------------------------------------

  private def makeWorkbook(
    issues:  Seq[IssueReportService.IssueRow],
    periods: Map[Int, IssuePeriod] = Map.empty
  ): XSSFWorkbook = {
    val out = new ByteArrayOutputStream()
    IssueReportService.generateExcel(issues, periods, out)
    new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray))
  }

  private def row(
    issueId:      Int    = 1,
    title:        String = "title",
    closed:       Boolean = false,
    assignee:     String = "",
    milestone:    String = "",
    labels:       String = "",
    createdAt:    String = "",
    updatedAt:    String = "",
    milestoneDueDate: String = "",
    closedDate:   String = "",
    creator:      String = "alice",
    body:         String = "",
    commentCount: Int    = 0,
    url:          String = ""
  ): IssueReportService.IssueRow =
    IssueReportService.IssueRow(
      issueId = issueId,
      title = title,
      closed = closed,
      assignee = assignee,
      milestone = milestone,
      labels = labels,
      createdAt = createdAt,
      updatedAt = updatedAt,
      milestoneDueDate = milestoneDueDate,
      closedDate = closedDate,
      creator = creator,
      body = body,
      commentCount = commentCount,
      url = url
    )

  test("generateExcel: Issueなしでも有効なExcelを生成できる") {
    val wb    = makeWorkbook(Seq.empty)
    val sheet = wb.getSheet("Issues")
    sheet should not be null
    wb.close()
  }

  test("generateExcel: ganttなしのヘッダーは17列") {
    val wb      = makeWorkbook(Seq.empty)
    val sheet   = wb.getSheet("Issues")
    val lastCol = sheet.getRow(0).getLastCellNum
    lastCol shouldBe 17
    wb.close()
  }

  test("generateExcel: ganttありのヘッダーは21列") {
    val issue   = row(issueId = 1, title = "Test")
    val periods = Map(1 -> IssuePeriod(1, Some("2026-03-01"), Some("2026-03-31"), Some(50), None))
    val wb      = makeWorkbook(Seq(issue), periods)
    val sheet   = wb.getSheet("Issues")
    sheet.getRow(0).getLastCellNum shouldBe 21
    wb.close()
  }

  test("generateExcel: ヘッダー1列目はIssue#") {
    val wb = makeWorkbook(Seq.empty)
    wb.getSheet("Issues").getRow(0).getCell(0).getStringCellValue shouldBe "Issue#"
    wb.close()
  }

  test("generateExcel: ヘッダにオートフィルタが設定される") {
    val wb    = makeWorkbook(Seq.empty)
    val sheet = wb.getSheet("Issues").asInstanceOf[XSSFSheet]
    sheet.getCTWorksheet.isSetAutoFilter shouldBe true
    sheet.getCTWorksheet.getAutoFilter.getRef shouldBe "A1:Q1"
    wb.close()
  }

  test("generateExcel: Issueデータが正しく出力される") {
    val issue = row(
      issueId      = 42,
      title        = "テスト Issue",
      closed       = false,
      assignee     = "bob",
      milestone    = "v1.0",
      labels       = "bug, enhancement",
      createdAt    = "2026/01/01 09:00",
      updatedAt    = "2026/01/02 10:00",
      milestoneDueDate = "2026/01/15",
      closedDate   = "",
      creator      = "alice",
      body         = "本文テスト",
      commentCount = 3,
      url          = "http://localhost:29418/alice/repo/issues/42"
    )
    val wb    = makeWorkbook(Seq(issue))
    val sheet = wb.getSheet("Issues")
    val row1  = sheet.getRow(1)

    row1.getCell(0).getNumericCellValue  shouldBe 42.0
    row1.getCell(1).getStringCellValue   shouldBe "テスト Issue"
    row1.getCell(1).getHyperlink.getAddress shouldBe "http://localhost:29418/alice/repo/issues/42"
    row1.getCell(2).getStringCellValue   shouldBe "本文テスト"
    row1.getCell(3).getStringCellValue   shouldBe "open"
    row1.getCell(4).getStringCellValue   shouldBe "alice"
    row1.getCell(5).getStringCellValue   shouldBe "bob"
    row1.getCell(6).getStringCellValue   shouldBe "bug, enhancement"
    row1.getCell(7).getStringCellValue   shouldBe "v1.0"
    row1.getCell(8).getNumericCellValue  shouldBe 3.0
    row1.getCell(9).getStringCellValue   shouldBe "2026/01/01 09:00"
    row1.getCell(10).getStringCellValue  shouldBe "2026/01/02 10:00"
    row1.getCell(11).getStringCellValue  shouldBe ""
    row1.getCell(12).getStringCellValue  shouldBe "http://localhost:29418/alice/repo/issues/42"
    row1.getCell(13).getStringCellValue  shouldBe ""
    row1.getCell(14).getStringCellValue  shouldBe ""
    row1.getCell(15).getStringCellValue  shouldBe ""
    row1.getCell(16).getStringCellValue  shouldBe "2026/01/15"
    wb.close()
  }

  test("generateExcel: closedのIssueは状態がclosed") {
    val issue = row(issueId = 1, title = "Closed", closed = true, closedDate = "2026/01/01 09:00")
    val wb    = makeWorkbook(Seq(issue))
    wb.getSheet("Issues").getRow(1).getCell(3).getStringCellValue shouldBe "closed"
    wb.close()
  }

  test("generateExcel: 本文がMaxCellLengthを超える場合は切り詰められる") {
    val longBody = "x" * (IssueReportService.MaxCellLength + 100)
    val issue    = row(body = longBody)
    val wb       = makeWorkbook(Seq(issue))
    val bodyVal  = wb.getSheet("Issues").getRow(1).getCell(2).getStringCellValue
    bodyVal.length shouldBe IssueReportService.MaxCellLength
    wb.close()
  }

  test("generateExcel: gantt期間データがセルに反映される") {
    val issue   = row(issueId = 5, title = "Gantt Issue")
    val periods = Map(5 -> IssuePeriod(5, Some("2026-03-01"), Some("2026-03-31"), Some(75), Some("3")))
    val wb      = makeWorkbook(Seq(issue), periods)
    val row1    = wb.getSheet("Issues").getRow(1)

    row1.getCell(17).getStringCellValue  shouldBe "2026-03-01"
    row1.getCell(18).getStringCellValue  shouldBe "2026-03-31"
    row1.getCell(19).getNumericCellValue shouldBe 75.0
    row1.getCell(20).getStringCellValue  shouldBe "3"
    wb.close()
  }

  test("generateExcel: ganttデータがないIssueはgantt列が空文字") {
    val issue1  = row(issueId = 1, title = "With Gantt")
    val issue2  = row(issueId = 2, title = "Without Gantt")
    val periods = Map(1 -> IssuePeriod(1, Some("2026-03-01"), Some("2026-03-31"), Some(50), None))
    val wb      = makeWorkbook(Seq(issue1, issue2), periods)
    val sheet   = wb.getSheet("Issues")

    sheet.getRow(2).getCell(17).getStringCellValue shouldBe ""
    sheet.getRow(2).getCell(18).getStringCellValue shouldBe ""
    wb.close()
  }
}

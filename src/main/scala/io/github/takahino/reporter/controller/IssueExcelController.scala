package io.github.takahino.reporter.controller

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.ReadableUsersAuthenticator
import gitbucket.core.util.Implicits._
import io.github.takahino.reporter.service.{IssueNoteRepository, IssuePeriodRepository, IssueReportService}
import org.slf4j.LoggerFactory

import scala.util.Try

class IssueReportController
    extends ControllerBase
    with RepositoryService
    with AccountService
    with ReadableUsersAuthenticator {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * GET /:owner/:repository/issues/export-excel
   * リポジトリの全 issue（open/closed）を Excel 形式でエクスポートする。
   */
  get("/:owner/:repository/issues/export-excel")(readableUsersOnly { repository =>
    val owner    = repository.owner
    val repoName = repository.name

    // Slick セッションから JDBC コネクションを取得
    implicit val session = request2Session(request)
    val conn = session.conn

    // ベース URL を構築（Issue の URL 列に使用）
    val scheme = request.getScheme
    val host   = request.getServerName
    val port   = request.getServerPort
    val baseUrl = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443))
      s"$scheme://$host"
    else
      s"$scheme://$host:$port"

    // 全 issue を raw SQL で取得し、備考・確認待ち・期間を統合
    val notes   = IssueNoteRepository.findAllNotes(conn, owner, repoName)
    val periods = IssuePeriodRepository.findAllPeriods(conn, owner, repoName)
    val issues  = IssueReportService.mergeWithPeriods(
      IssueReportService.mergeWithNotes(
        IssueReportService.loadIssues(conn, owner, repoName, baseUrl), notes),
      periods)

    val filename    = s"issues-${owner}-${repoName}.xlsx"
    val encodedName = IssueReportService.encodeFilename(filename)

    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    response.setHeader(
      "Content-Disposition",
      s"""attachment; filename="${filename}"; filename*=UTF-8''${encodedName}"""
    )

    Try {
      IssueReportService.generateExcel(issues, response.getOutputStream)
    }.recover { case e =>
      logger.error("Excel generation failed", e)
      halt(500, "Excel の生成中にエラーが発生しました。")
    }.get
  })
}

package io.github.takahino.reporter.model

import java.time.LocalDateTime

/** リポジトリ単位のメール送信スケジュール。
 *  recipients: GitBucket ユーザー名のカンマ区切り文字列。
 *  送信時に ACCOUNT テーブルから実際のメールアドレスを引く。
 *  SMTP設定は GitBucket 管理画面の設定（SystemSettings）をそのまま使用する。
 */
case class MailSchedule(
  id:          Int,
  owner:       String,
  repository:  String,
  recipients:  String,    // GitBucketユーザー名 カンマ区切り (例: "alice,bob")
  hour:        Int,       // 0-23
  minute:      Int,       // 0-59
  daysOfWeek:  String,    // "1,2,3,4,5" (1=Mon…7=Sun)
  enabled:     Boolean,
  lastSentAt:  Option[LocalDateTime],
  columnOrder: String     = ""  // カンマ区切りの列キー。"" = 全20列デフォルト順
)

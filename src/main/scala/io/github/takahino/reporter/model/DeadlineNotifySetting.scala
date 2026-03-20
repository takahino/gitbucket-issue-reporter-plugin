package io.github.takahino.reporter.model

/**
 * リポジトリ単位の期日通知設定。
 *
 * 通知トリガー:
 *   - advanceNoticeDays      : 期日のN日前に1度だけ通知（カンマ区切りで複数指定可。例: "30,14,7"）
 *   - dailyNotifyWithinDays  : 期日のN日以内は毎日通知（0=無効）
 *   - notifyOverdue          : 期日超過は毎日通知
 *   - notifyNoDeadline       : 期日なしのIssueも毎日通知
 *   - notifyNotStarted       : 開始予定日超過 & 進捗0% のIssueを毎日通知
 *
 * 通知先:
 *   - notifyToCreator        : Issue作成者に通知
 *   - notifyToAssignee       : Issue担当者に通知
 *
 * sendHour/sendMinute/daysOfWeek : 送信時刻・曜日フィルタ
 * sortOrder : メール内 Issue のソート順
 *   "NO_DEADLINE_FIRST" - 期日なし先頭 → 期日近い順
 *   "DUE_DATE_ASC"      - 期日近い順 → 期日なし末尾
 *   "ISSUE_ID_ASC"      - Issue ID 昇順
 */
case class DeadlineNotifySetting(
  id:                    Int,
  owner:                 String,
  repository:            String,
  advanceNoticeDays:     String,   // "30,7" のようにカンマ区切り（空文字=通知なし）
  dailyNotifyWithinDays: Int,      // N日以内は毎日通知 (0=無効)
  notifyOverdue:         Boolean,  // 期日超過は毎日
  notifyNoDeadline:      Boolean,  // 期日なしは毎日
  notifyToCreator:       Boolean,  // 作成者に送信
  notifyToAssignee:      Boolean,  // 担当者に送信
  sendHour:              Int,      // 0-23
  sendMinute:            Int,      // 0-59
  daysOfWeek:            String,   // "1,2,3,4,5" (1=Mon…7=Sun)
  enabled:               Boolean,
  sortOrder:             String,   // "NO_DEADLINE_FIRST" | "DUE_DATE_ASC" | "ISSUE_ID_ASC"
  notifyNotStarted:      Boolean   // 開始予定日超過 & 進捗0% 通知
)

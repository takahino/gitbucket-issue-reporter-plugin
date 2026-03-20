# GitBucket Issue Reporter Plugin

## 背景

[GitBucket](https://github.com/gitbucket/gitbucket) は WAR ファイルひとつで動作する、導入コストの低い Git リポジトリ管理サーバーです。
その手軽さを活かし、専用の課題管理ツールを別途導入せずとも、**GitBucket の Issue 機能だけでチームの課題管理を完結させる**ことを目的として本プラグインを作成しました。

Issue の内容をそのまま Excel で出力・共有できるようにすることで、開発ツールに不慣れなメンバーやステークホルダーへの報告・連携をスムーズにします。

---

GitBucket の Issue 一覧を Excel (XLSX) でダウンロード、定期メール送信、期日通知、Issue 備考・確認待ち・期間管理を行うプラグインです。

## 機能

### 1. Issue 一覧（フィルタ＆ソート）
- ブラウザ上でリポジトリの全 Issue を一覧表示
- 担当者・状態・マイルストーン・ラベル・確認待ち でドロップダウンフィルタ
- 作成日・更新日・開始予定日・完了予定日の範囲フィルタ
- 全列インクリメンタルテキスト検索
- 列ヘッダクリックで昇順/降順ソート
- 開始予定日・完了予定日・進捗(%) 列を常時表示

### 2. Excel エクスポート
- リポジトリの全 Issue (open/closed、PR 除く) を XLSX ファイルでダウンロード
- 常に 20 列（開始予定日・完了予定日・進捗を含む）で出力

#### 出力列

| 列 | 内容 |
|---|---|
| Issue# | Issue 番号 |
| タイトル | Issue タイトル（クリックで Issue ページを開くハイパーリンク付き） |
| 本文 | Issue 本文 |
| 状態 | `open` または `closed` |
| 作成者 | Issue を作成したユーザー名 |
| 担当者 | アサインされたユーザー名 |
| ラベル | 付与されたラベル（カンマ区切り） |
| マイルストーン | マイルストーン名 |
| コメント数 | コメントの件数（アサイン・ラベル等のアクションイベントを除く） |
| 作成日時 | Issue の作成日時 |
| 更新日時 | Issue の最終更新日時 |
| クローズ日 | クローズされた日時（open の場合は空） |
| URL | Issue の URL |
| 備考 | Issue 個別ページで入力した備考テキスト |
| 確認待ち | 確認待ち状態の場合 `○` |
| 確認詳細 | 確認待ちの詳細（確認者・期限など） |
| マイルストーン期日 | マイルストーンの期日 |
| 開始予定日 | Issue の開始予定日 |
| 完了予定日 | Issue の完了予定日 |
| 進捗(%) | 進捗率（0〜100） |

### 3. Issue メール定期送信
- Issue 一覧 Excel を指定した時刻・曜日に GitBucket ユーザーへ自動送信
- 複数の宛先を設定可能（GitBucket ユーザー名のカンマ区切り）

### 4. Issue 備考・確認待ち・期間管理
Issue 個別ページ (`/:owner/:repository/issues/:id`) に入力パネルを自動表示。

#### 備考・確認待ちパネル
- **確認待ちチェックボックス** — 作業者が完了し関係者の確認待ち状態を記録
- **確認詳細** — 確認者・期限など最大 1000 文字の自由記述
- **備考** — その他メモを最大 4000 文字まで記録
- いずれも Excel エクスポート時に「確認待ち」「確認詳細」「備考」列として出力

#### 期間・進捗パネル
- **開始予定日** — Issue の開始予定日（`yyyy-MM-dd` 形式）
- **完了予定日** — Issue の完了予定日（`yyyy-MM-dd` 形式）
- **進捗(%)** — 0〜100 の数値で進捗率を管理
- いずれも Excel エクスポート・Issue 一覧に反映

### 5. 期日通知
以下の条件に応じてメール通知を自動送信：

| 通知タイプ | 条件 |
|---|---|
| N日前（1度のみ） | 期日までぴったり N 日前（複数の N を指定可能。例: 30, 14, 7） |
| N日以内（毎日） | 期日まで N 日以内（N は自由設定、0 で無効） |
| 期日超過（毎日） | 期日を超過した Issue |
| 期日なし（毎日） | 期日が未設定の Issue |

- 「N日以内毎日」が有効な場合、その範囲内の日数は「N日前1度のみ」より優先されます
- 通知先は Issue 作成者・担当者から選択可能

## 動作環境

- GitBucket 4.36.2
- Java 11+

## インストール

1. [Releases](../../releases) から `gitbucket-issue-reporter-plugin-0.1.0.jar` をダウンロード
2. `~/.gitbucket/plugins/` に配置
3. GitBucket を再起動

## ビルド

### 必要環境
- JDK 11+
- SBT 1.9.9

### ビルド手順

```bash
sbt assembly
```

出力: `target/scala-2.13/gitbucket-issue-reporter-plugin-0.1.0.jar`

#### thin JAR（Apache POI を除外）
POI が別途提供される環境向け：

```bash
sbt -Dthin=true assembly
```

出力: `target/scala-2.13/gitbucket-issue-reporter-plugin-0.1.0-thin.jar`

### デプロイ

```bash
cp target/scala-2.13/gitbucket-issue-reporter-plugin-0.1.0.jar ~/.gitbucket/plugins/
```

## 使い方

プラグインを有効化すると、Issue 一覧ページ (`/:owner/:repository/issues`) に以下のボタンが追加されます。

| ボタン | 権限 | 機能 |
|---|---|---|
| Issue一覧 | 閲覧者以上 | フィルタ・ソート付きの Issue 一覧ページを表示 |
| Excel ダウンロード | 閲覧者以上 | 現在のリポジトリの全 Issue を XLSX でダウンロード |
| 定期Excel送信 | オーナーのみ | 定期メール送信のスケジュール設定 |
| 期日通知設定 | オーナーのみ | 期日通知の条件・送信先設定 |

> **Note**: 「定期Excel送信」「期日通知設定」ボタンはオーナー権限を持つユーザーにのみ表示されます。

### Issue 一覧ページ

`/:owner/:repository/issues/issue-table` でアクセスできるフィルタ付き Issue 一覧ページです。

| フィルタ | 説明 |
|---|---|
| 担当者 | ドロップダウンで担当者を絞り込み |
| 状態 | open / closed で絞り込み |
| マイルストーン | ドロップダウンで絞り込み |
| ラベル | ドロップダウンで絞り込み |
| 確認待ち | 確認待ちのみ / 確認待ちでない で絞り込み |
| テキスト検索 | 全列を対象にインクリメンタル検索 |
| 作成日・更新日 | 日付範囲で絞り込み |
| 開始予定日・完了予定日 | 日付範囲で絞り込み |

列ヘッダをクリックすると昇順/降順でソートできます。

### Issue 個別ページ

Issue 個別ページ (`/:owner/:repository/issues/:id`) では、Issue 本文の直下に2つの入力パネルが表示されます。「保存」ボタンで保存され、Excel エクスポートおよび Issue 一覧に反映されます。

#### 備考・確認待ちパネル

| 項目 | 内容 |
|---|---|
| 確認待ち | チェックボックス。作業者完了・関係者確認待ち状態を記録 |
| 確認詳細 | 確認者・期限などを自由記述（最大 1000 文字） |
| 備考 | その他メモ（最大 4000 文字） |

#### 期間・進捗パネル

| 項目 | 内容 |
|---|---|
| 開始予定日 | Issue の開始予定日 |
| 完了予定日 | Issue の完了予定日 |
| 進捗(%) | 0〜100 の数値で進捗率を入力 |

### メール送信設定

- **送信先ユーザー**: GitBucket に登録されたユーザーをチェックボックスで選択（複数選択可）
- **送信時刻**: 時・分を指定
- **曜日**: 送信する曜日をチェック（月〜日）
- SMTP 設定は GitBucket 管理画面 (`/_admin/system`) のものを使用
- 設定保存後、**「今すぐ送信」** ボタンでテスト送信が可能
- **「削除」** ボタンで設定を削除

### 期日通知設定

- **N日前通知（1度のみ）**: 何日前に通知するかをカンマ区切りで指定（例: `30,14,7`）。空欄で無効
- **N日以内毎日通知**: 期日まで何日以内になったら毎日通知するかを数値で指定（`0` で無効）
- **期日超過・期日なし**: それぞれ毎日通知するか有効/無効
- **通知先**: Issue 作成者・担当者をそれぞれ選択
- **メール内ソート順**: 以下の3つから選択
  - 期日なし先頭 → 期日近い順（デフォルト）
  - 期日近い順（期日なし末尾）
  - Issue ID 昇順
- **送信時刻・曜日**: 時・分・曜日を指定
- 設定保存後、**「今すぐ送信」** ボタンで即時テスト送信が可能（送信済みチェックをスキップ）
- **「削除」** ボタンで設定を削除

### 期日の取得元

期日通知における期日の判定は以下の優先順位で行います：

1. 本プラグインが管理する `REPORTER_ISSUE_PERIOD.END_DATE`（Issue 個別ページで入力）
2. GitBucket マイルストーンの `DUE_DATE`

## データベース

プラグインは以下のテーブルを自動作成します（GitBucket 組み込みの H2 データベース）。

### EXCEL_MAIL_SCHEDULE

メール定期送信設定を格納します。リポジトリごとに 1 レコード。

| カラム名 | 型 | 説明 |
|---|---|---|
| `ID` | INTEGER (PK) | 自動採番 |
| `OWNER` | VARCHAR(100) | リポジトリオーナー名 |
| `REPOSITORY` | VARCHAR(100) | リポジトリ名 |
| `RECIPIENTS` | VARCHAR(2000) | 送信先ユーザー名（カンマ区切り） |
| `SEND_HOUR` | SMALLINT | 送信時刻（時） |
| `SEND_MINUTE` | SMALLINT | 送信時刻（分） |
| `DAYS_OF_WEEK` | VARCHAR(20) | 送信曜日（`1`〜`7` のカンマ区切り、1=月） |
| `ENABLED` | BOOLEAN | スケジュール有効/無効 |
| `LAST_SENT_AT` | TIMESTAMP | 最終送信日時 |

### REPORTER_DEADLINE_NOTIFY_SETTING

期日通知設定を格納します。リポジトリごとに 1 レコード。

| カラム名 | 型 | デフォルト | 説明 |
|---|---|---|---|
| `ID` | INTEGER (PK) | — | 自動採番 |
| `OWNER` | VARCHAR(100) | — | リポジトリオーナー名 |
| `REPOSITORY` | VARCHAR(100) | — | リポジトリ名 |
| `ADVANCE_NOTICE_DAYS` | VARCHAR(100) | `30,7` | N日前に1度だけ通知する日数（カンマ区切り、例: `30,14,7`） |
| `DAILY_NOTIFY_WITHIN_DAYS` | SMALLINT | 7 | N日以内は毎日通知（0=無効） |
| `NOTIFY_OVERDUE` | BOOLEAN | FALSE | 期日超過の毎日通知 |
| `NOTIFY_NO_DEADLINE` | BOOLEAN | FALSE | 期日未設定の毎日通知 |
| `NOTIFY_TO_CREATOR` | BOOLEAN | TRUE | Issue 作成者へ通知 |
| `NOTIFY_TO_ASSIGNEE` | BOOLEAN | TRUE | 担当者へ通知 |
| `SEND_HOUR` | SMALLINT | 9 | 送信時刻（時） |
| `SEND_MINUTE` | SMALLINT | 0 | 送信時刻（分） |
| `DAYS_OF_WEEK` | VARCHAR(20) | `1,2,3,4,5` | 送信曜日（平日デフォルト） |
| `ENABLED` | BOOLEAN | FALSE | 通知有効/無効 |
| `SORT_ORDER` | VARCHAR(30) | `NO_DEADLINE_FIRST` | 通知メール内のIssue並び順 |

### REPORTER_DEADLINE_NOTIFY_LOG

期日通知の送信履歴を格納します。同一 Issue への重複通知を防ぐために使用。90日経過したレコードは自動削除されます。

| カラム名 | 型 | 説明 |
|---|---|---|
| `ID` | INTEGER (PK) | 自動採番 |
| `OWNER` | VARCHAR(100) | リポジトリオーナー名 |
| `REPOSITORY` | VARCHAR(100) | リポジトリ名 |
| `ISSUE_ID` | INTEGER | Issue 番号 |
| `NOTIFY_TYPE` | VARCHAR(30) | 通知種別（`BEFORE_30`, `BEFORE_7`, `DAILY`, `OVERDUE`, `NO_DEADLINE` など） |
| `SENT_DATE` | DATE | 送信日 |

### REPORTER_ISSUE_NOTE

Issue ごとの備考・確認待ち情報を格納します。

| カラム名 | 型 | 説明 |
|---|---|---|
| `OWNER` | VARCHAR(100) (PK) | リポジトリオーナー名 |
| `REPOSITORY_NAME` | VARCHAR(100) (PK) | リポジトリ名 |
| `ISSUE_ID` | INTEGER (PK) | Issue 番号 |
| `NOTE` | VARCHAR(4000) | 備考テキスト（最大 4000 文字） |
| `WAITING_FOR_CONFIRMATION` | BOOLEAN | 確認待ち状態（デフォルト FALSE） |
| `CONFIRMATION_DETAIL` | VARCHAR(1000) | 確認待ち詳細（最大 1000 文字） |

### REPORTER_ISSUE_PERIOD

Issue ごとの期間・進捗情報を格納します。

| カラム名 | 型 | 説明 |
|---|---|---|
| `OWNER` | VARCHAR(100) (PK) | リポジトリオーナー名 |
| `REPOSITORY_NAME` | VARCHAR(100) (PK) | リポジトリ名 |
| `ISSUE_ID` | INTEGER (PK) | Issue 番号 |
| `START_DATE` | VARCHAR(10) | 開始予定日（`yyyy-MM-dd` 形式、未設定可） |
| `END_DATE` | VARCHAR(10) | 完了予定日（`yyyy-MM-dd` 形式、未設定可） |
| `PROGRESS` | INTEGER | 進捗率（0〜100、未設定可） |

## ライセンス

MIT License

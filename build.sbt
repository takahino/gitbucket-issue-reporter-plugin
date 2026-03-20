organization := "io.github.takahino"
name         := "gitbucket-issue-reporter-plugin"
version      := "0.1.0"
scalaVersion := "2.13.16"

resolvers += "GitBucket" at "https://repo1.maven.org/maven2/"

// -Dthin=true の場合は POI を provided にして薄い JAR を生成
val isThin = sys.props.get("thin").contains("true")

libraryDependencies ++= Seq(
  "io.github.gitbucket" %% "gitbucket"        % "4.36.2" % "provided",
  "javax.servlet"        % "javax.servlet-api" % "3.1.0"  % "provided",
  "org.apache.poi"       % "poi-ooxml"         % "5.2.5"  % (if (isThin) "provided" else "compile"),
  "ch.qos.logback"       % "logback-classic"   % "1.2.11" % "provided",
  // GitBucket の Mailer / HtmlEmail / ByteArrayDataSource を使うためのコンパイル依存
  // 実行時は GitBucket 本体が提供するため provided
  "com.sun.mail"         % "javax.mail"        % "1.6.2"  % "provided",
  "org.apache.commons"   % "commons-email"     % "1.5"    % "provided",
  // テスト用
  "org.scalatest"        %% "scalatest"        % "3.2.17" % Test,
  "com.h2database"        % "h2"               % "2.2.224" % Test
)

enablePlugins(AssemblyPlugin)

// fat JAR: gitbucket-issue-reporter-plugin-0.1.0.jar
// thin JAR: gitbucket-issue-reporter-plugin-0.1.0-thin.jar
assembly / assemblyJarName := {
  if (isThin) s"${name.value}-${version.value}-thin.jar"
  else        s"${name.value}-${version.value}.jar"
}

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "mailcap")                      => MergeStrategy.first
  case PathList("META-INF", x) if x.startsWith("javamail") => MergeStrategy.first
  case PathList("META-INF", "services", xs @ _*)            => MergeStrategy.filterDistinctLines
  case PathList("META-INF", xs @ _*)                        => MergeStrategy.discard
  case PathList("reference.conf")                           => MergeStrategy.concat
  case _                                                    => MergeStrategy.first
}

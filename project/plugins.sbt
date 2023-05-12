addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.9.0")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.1.1")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.0")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.4")
addSbtPlugin("com.github.cb372" % "sbt-explicit-dependencies" % "0.2.16")
addDependencyTreePlugin

/**
  * Force the version scheme for scala-xml to not use semantic versioning to anticipate a binary incompatibility
  * This currently was already happening for some build servers, but bloop seems to struggle with this.
  */
libraryDependencySchemes += "org.scala-lang.modules" % "scala-xml_2.12" % VersionScheme.Always

package spyt

import sbt.{IO, Project, SettingKey, State, TaskKey}
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities.stateW
import sbtrelease._
import spyt.ReleaseUtils._
import spyt.SpytPlugin.autoImport._

import java.io.File

object SpytRelease {
  lazy val clientReleaseProcess: Seq[ReleaseStep] = testProcess ++ Seq(
    clientReleaseVersions,
    setReleaseClientVersion,
    setYtProxies,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion)),
    ReleaseStep(releaseStepTask(spytPublishClient)),
    ReleaseStep(releaseStepTask(spytPublishLibraries)),
    commitReleaseClientVersion,
    setNextClientVersion,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion)),
    commitNextClientVersion,
    maybePushChanges,
    logClientVersion
  )

  lazy val clusterReleaseProcess: Seq[ReleaseStep] = testProcess ++ Seq(
    minorReleaseVersions,
    setReleaseClusterVersion,
    setReleaseClientVersion,
    setYtProxies,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion)),
    ReleaseStep(releaseStepTask(spytPublishCluster)),
    ReleaseStep(releaseStepTask(spytPublishClient)),
    commitReleaseClusterVersion,
    setNextClientVersion,
    setNextClusterVersion,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion)),
    commitNextClusterVersion,
    maybePushChanges,
    logClusterVersion,
    logClientVersion
  )

  lazy val sparkForkReleaseProcess: Seq[ReleaseStep] = testProcess ++ Seq(
    minorReleaseVersions,
    setReleaseClusterVersion,
    setReleaseClientVersion,
    sparkForkReleaseVersions,
    setSparkForkReleaseVersion,
    setYtProxies,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion))
  ) ++ sparkMvnDeployProcess ++ Seq(
    ReleaseStep(releaseStepTask(spytPublishSparkFork)),
    ReleaseStep(releaseStepTask(spytPublishCluster)),
    ReleaseStep(releaseStepTask(spytPublishClient)),
    commitReleaseClusterVersion,
    commitReleaseSparkForkVersion,
    setNextClientVersion,
    setNextClusterVersion,
    ReleaseStep(releaseStepTask(spytUpdatePythonVersion)),
    commitNextClusterVersion,
    maybePushChanges,
    logSparkForkVersion,
    logClusterVersion,
    logClientVersion
  )

  private lazy val testProcess: Seq[ReleaseStep] = Seq(
    checkSnapshotDependencies,
    runClean,
    runTest
  )

  private def releaseVersions(versions: SettingKey[Versions],
                              st: State,
                              versionSetting: SettingKey[String]): State = {
    val extracted = Project.extract(st)
    st.log.info(s"Teamcity build: $isTeamCity")

    val releaseV = if (isTeamCity) {
      sys.env("BUILD_NUMBER")
    } else {
      val currentV = extracted.get(versionSetting)
      val releaseFunc = extracted.runTask(releaseVersion, st)._2
      releaseFunc(currentV)
    }
    st.log.info(s"Release version: $releaseV")

    val nextFunc = extracted.runTask(releaseNextVersion, st)._2
    val nextV = nextFunc(releaseV)
    st.log.info(s"Next version: $nextV")

    st.put(versions.key, (releaseV, nextV))
  }

  private def releaseMinorVersions(versions: SettingKey[Versions],
                                   st: State,
                                   versionSetting: SettingKey[String]): State = {
    val extracted = Project.extract(st)

    val currentV = extracted.get(versionSetting)

    val releaseV = Version(currentV).map(_.bump(Version.Bump.Minor).withoutQualifier.string)
      .getOrElse(versionFormatError(currentV))
    st.log.info(s"Release version: $releaseV")

    val nextFunc = extracted.runTask(releaseNextVersion, st)._2
    val nextV = nextFunc(releaseV)
    st.log.info(s"Next version: $nextV")

    st.put(versions.key, (releaseV, nextV))
  }

  private def vcs(st: State): Option[Vcs] = st.extract.get(releaseVcs)

  private def commit(st: State,
                     commitMessage: TaskKey[String],
                     files: Seq[SettingKey[File]]): State = {
    val log = st.log
    vcs(st).map { git =>
      val addFiles = files.map(f => st.extract.get(f).getCanonicalFile)
      val base = git.baseDir.getCanonicalFile
      val sign = st.extract.get(releaseVcsSign)
      val signOff = st.extract.get(releaseVcsSignOff)
      val relativePaths = addFiles.map(f => IO.relativize(base, f)
        .getOrElse("Version file [%s] is outside of this VCS repository with base directory [%s]!" format(f, base)))

      relativePaths.foreach(p => git.add(p) !! log)
      val status = git.status.!!.trim

      if (status.nonEmpty) {
        val (state, msg) = st.extract.runTask(commitMessage, st)
        git.commit(msg, sign, signOff) ! log
        state
      } else {
        // nothing to commit. this happens if the version.sbt file hasn't changed.
        st
      }
    }.getOrElse({
      log.error("No version control system detected.  Changes not committed.")
      st
    })
  }

  private def maybeCommit(st: State,
                          commitMessage: TaskKey[String],
                          files: Seq[SettingKey[File]]): State = {
    if (isTeamCity) {
      st
    } else {
      commit(st, commitMessage, files)
    }
  }

  lazy val setYtProxies: ReleaseStep = { st: State =>
    System.setProperty("proxies", Seq("hume", "hahn", "arnold", "vanga").mkString(","))
    st
  }

  private def getReleaseVersion(vs: Versions): String = vs._1

  private def getReleasePythonVersion(vs: Versions): String = vs._1.split("-fork-").mkString("+")

  private def getNextVersion(vs: Versions): String = vs._2

  private def getNextPythonVersion(vs: Versions): String = vs._2.replace("-SNAPSHOT", "b1")

  private lazy val maybePushChanges: ReleaseStep = if (isTeamCity) identity[State](_) else pushChanges

  private lazy val setReleaseClusterVersion: ReleaseStep = {
    setVersion(clusterVersions, Seq(spytClusterVersion -> getReleaseVersion), spytClusterVersionFile)
  }
  private lazy val setNextClusterVersion: ReleaseStep = {
    maybeSetVersion(clusterVersions, Seq(spytClusterVersion -> getNextVersion), spytClusterVersionFile)
  }
  private lazy val commitReleaseSparkForkVersion: ReleaseStep = { st: State =>
    maybeCommit(
      st,
      releaseSparkForkCommitMessage,
      Seq(spytSparkVersionFile, spytSparkDependencyFile)
    )
  }
  private lazy val commitReleaseClusterVersion: ReleaseStep = { st: State =>
    maybeCommit(
      st,
      releaseClusterCommitMessage,
      Seq(spytClientVersionFile, spytClientVersionPyFile, spytClusterVersionFile)
    )
  }
  private lazy val commitNextClusterVersion: ReleaseStep = { st: State =>
    maybeCommit(
      st,
      releaseNextClusterCommitMessage,
      Seq(spytClientVersionFile, spytClientVersionPyFile, spytClusterVersionFile)
    )
  }
  private lazy val clientReleaseVersions: ReleaseStep = { st: State =>
    releaseVersions(clientVersions, st, spytClientVersion)
  }
  private lazy val setReleaseClientVersion: ReleaseStep = {
    setVersion(clientVersions, Seq(
      spytClientVersion -> getReleaseVersion,
      spytClientPythonVersion -> getReleaseVersion
    ), spytClientVersionFile)
  }
  private lazy val setNextClientVersion: ReleaseStep = {
    maybeSetVersion(clientVersions, Seq(
      spytClientVersion -> getNextVersion,
      spytClientPythonVersion -> getNextPythonVersion
    ), spytClientVersionFile)
  }
  private lazy val commitReleaseClientVersion: ReleaseStep = { st: State =>
    maybeCommit(st, releaseClientCommitMessage, Seq(spytClientVersionFile, spytClientVersionPyFile))
  }
  private lazy val commitNextClientVersion: ReleaseStep = { st: State =>
    maybeCommit(st, releaseNextClientCommitMessage, Seq(spytClientVersionFile, spytClientVersionPyFile))
  }

  private lazy val minorReleaseVersions: ReleaseStep = { st: State =>
    val st2 = releaseMinorVersions(clusterVersions, st, spytClusterVersion)
    releaseMinorVersions(clientVersions, st2, spytClientVersion)
  }
  private lazy val sparkForkReleaseVersions: ReleaseStep = { st: State =>
    val extracted = Project.extract(st)

    val currentClusterVersion = extracted.get(spytClusterVersion)
    val currentSparkMainVersion = extracted.get(spytSparkVersion).split("-").head

    val releaseV = s"$currentSparkMainVersion-fork-$currentClusterVersion"
    st.log.info(s"Release version: $releaseV")

    st.put(sparkVersions.key, (releaseV, ""))
  }
  private lazy val setSparkForkReleaseVersion: ReleaseStep = {
    setVersion(sparkVersions,
      Seq(
        spytSparkVersion -> getReleaseVersion,
        spytSparkPythonVersion -> getReleasePythonVersion
      ), spytSparkVersionFile
    )
  }
  private lazy val sparkMvnDeployProcess: Seq[ReleaseStep] = Seq(
    setSparkForkSnapshotVersionMvn,
    ReleaseStep(releaseStepTask(spytMvnDeploySparkFork)),
    unsetSparkForkSnapshotVersionMvn,
    updateSparkForkDependency
  )

}

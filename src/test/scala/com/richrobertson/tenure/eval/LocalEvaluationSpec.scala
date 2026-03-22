package com.richrobertson.tenure.eval

import cats.effect.IO
import cats.effect.ExitCode
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files

class LocalEvaluationSpec extends CatsEffectSuite:
  override val munitTimeout = scala.concurrent.duration.DurationInt(90).seconds

  test("demo report includes the required milestone 8 scenarios") {
    LocalEvaluation.runDemo().map { report =>
      val scenarioNames = report.scenarios.map(_.name).toSet
      assertEquals(report.command, "demo")
      assertEquals(report.clusterSize, 3)
      assert(report.scenarios.forall(_.success))
      assert(scenarioNames.contains("cluster_bootstrap"))
      assert(scenarioNames.contains("lease_lifecycle"))
      assert(scenarioNames.contains("not_leader_read"))
      assert(scenarioNames.contains("idempotent_retry"))
      assert(scenarioNames.contains("fencing_turnover"))
      assert(scenarioNames.contains("failure_injection_delay"))
      assert(scenarioNames.contains("clean_shutdown_restart"))
    }
  }

  test("benchmark report remains stable and reviewer-friendly") {
    LocalEvaluation.runBenchmark(LocalEvaluation.BenchmarkCommand(iterations = 6, parallelism = 2)).map { report =>
      val latencyNames = report.latency.map(_.name).toSet
      assertEquals(report.command, "benchmark")
      assertEquals(report.clusterSize, 3)
      assertEquals(report.iterations, 6)
      assertEquals(report.parallelism, 2)
      assert(latencyNames == Set("acquire", "renew", "release"))
      assert(report.throughput.operations == 12)
      assert(report.failover.millisToSuccessfulRenew >= 0L)
      assert(report.recovery.millisToRecoveredFollowerView >= 0L)
      assert(report.knownLimits.nonEmpty)
    }
  }

  test("demo parsing rejects benchmark-only flags") {
    val result = LocalEvaluation.parseArgs(List("demo", "--iterations", "5"))
    assert(result.left.exists(_.contains("unknown option(s) for demo: iterations")))
  }

  test("argument parsing reports missing option values explicitly") {
    val result = LocalEvaluation.parseArgs(List("benchmark", "--work-dir"))
    assert(result.left.exists(_.contains("--work-dir requires a value")))
  }

  test("argument parsing reports invalid paths explicitly") {
    val invalidPath = s"${0.toChar}bad-path"
    val result = LocalEvaluation.parseArgs(List("demo", "--work-dir", invalidPath))
    assert(result.left.exists(_.contains("invalid --work-dir path")))
  }

  test("output paths create parent directories when needed") {
    IO.blocking(Files.createTempDirectory("tenure-eval-output")).flatMap { root =>
      val output = root.resolve("nested").resolve("benchmark.json")
      LocalEvaluation.run(List("benchmark", "--iterations", "1", "--parallelism", "1", "--output", output.toString)).map { exitCode =>
        assertEquals(exitCode, ExitCode.Success)
        assert(Files.exists(output))
      }
    }
  }

  test("execution failures return ExitCode.Error instead of raising") {
    IO.blocking(Files.createTempFile("tenure-eval-workdir-file", ".tmp")).flatMap { file =>
      LocalEvaluation.run(List("demo", "--work-dir", file.toString)).map { exitCode =>
        assertEquals(exitCode, ExitCode.Error)
      }
    }
  }

  test("provided work dir is treated as a parent and each run gets a fresh child directory") {
    IO.blocking(Files.createTempDirectory("tenure-eval-parent")).flatMap { parent =>
      val command = LocalEvaluation.DemoCommand(workDir = Some(parent))
      (LocalEvaluation.runDemo(command), LocalEvaluation.runDemo(command)).tupled.map { (first, second) =>
        val firstRoot = java.nio.file.Path.of(first.workDir)
        val secondRoot = java.nio.file.Path.of(second.workDir)
        assertEquals(firstRoot.getParent, parent)
        assertEquals(secondRoot.getParent, parent)
        assertNotEquals(firstRoot, secondRoot)
        assert(firstRoot.getFileName.toString.startsWith("run-"))
        assert(secondRoot.getFileName.toString.startsWith("run-"))
      }
    }
  }

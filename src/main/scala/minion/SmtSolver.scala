package se.gu
package minion

import java.util.Collection
import scala.collection.JavaConverters._

import de.uka.ilkd.key.java.Services

import de.uka.ilkd.key.logic.{ Term, TermFactory }
import de.uka.ilkd.key.logic.op.{ IProgramMethod, IProgramVariable, Junctor }

import de.uka.ilkd.key.proof.{ Proof }

import de.uka.ilkd.key.settings.{ ProofIndependentSettings, SMTSettings }

import de.uka.ilkd.key.smt.{
  SMTProblem, SMTSolverResult, SMTSolver, SolverLauncher,
  SolverLauncherListener, SolverType }
import de.uka.ilkd.key.smt.{ AbstractSolverType, SolverListener,
  SMTSolverImplementation, SmtLib2Translator }
import de.uka.ilkd.key.smt.AbstractSMTTranslator.Configuration;
import de.uka.ilkd.key.smt.model.Model


/**
 * A simplified SMT solver interface.
 *
 * FIXME: I'm not sure whether the KeY SMT solver API keeps solver
 * instances alive in the background or whether they are (re)started
 * upon every call to SolverLauncher.launch().  If the latter is the
 * case, this will probably degrade performance and using another SMT
 * API (e.g. ScalaSMT) might be more efficient here.  We could still
 * use the KeY SMT API for translating KeY formulas/terms to SMTlib.
 */
class SmtSolver (val solverType: SolverType, proof: Proof) {

  val services: Services = proof.getServices

  val settings: SMTSettings =
    new SMTSettings(proof.getSettings.getSMTSettings,
      ProofIndependentSettings.DEFAULT_INSTANCE.getSMTSettings, proof)

  /** Check the satisfiability of a given formula. */
  def check(formula: Term): (Option[Model], SMTSolverResult) = {
    // Setup the problem
    val problem = new SMTProblem(formula)

    // Setup and launch the solver
    //
    // FIXME: SolverLauncher objects can only be used once.  It would
    // be nice if we could launch a single solver instance/process and
    // keep it alive, rather than running individual solver processes
    // for each query we submit.  Note that this is a limitation of
    // the KeY SMT API, and could probably be avoided by switching to
    // another SMT library.
    val launcher = new SolverLauncher(settings)
    // val listener = new SolverLauncherListener {
    //   def launcherStarted(
    //     problems: Collection[SMTProblem],
    //     solverTypes: Collection[SolverType],
    //     launcher: SolverLauncher) { }
    //   def launcherStopped(
    //     launcher: SolverLauncher,
    //     finishedSolvers: Collection[SMTSolver]) {
    //     for (s <- finishedSolvers.asScala) {
    //       println(s.getTranslation)
    //       println(s.getSolverOutput)
    //       println(s.wasInterrupted)
    //     }
    //   }
    // }
    // launcher.addListener(listener)
    launcher.launch(problem, services, solverType)

    // for (s <- problem.getSolvers.asScala) {
    //   println(s.getTranslation)
    //   println(s.getSolverOutput)
    //   println(s.getProblem)
    // }

    val interruptedOpt = problem.getSolvers.asScala.find(_.wasInterrupted)
    if (!interruptedOpt.isEmpty) {
      val interrupted = interruptedOpt.get
      System.err.println(
        "ERROR: Solver was interrupted: " + interrupted)
      System.err.println("== Query submitted to solver ==")
      System.err.println(interrupted.getTranslation)
      System.err.println("== Solver output ==")
      System.err.println(interrupted.getSolverOutput)
      val exn = interrupted.getException
      if (exn != null) {
        System.err.println(exn)
        exn.printStackTrace(System.err)
      }
      Timers.abort(1)
    }

    // Extract the model, if possible.
    /*
     * FIXME: Model extraction only works using the Z3_CE (counter
     * example) solver type.  However, launching that type of solver
     * is not straight-forward.
     *
     *  1. The launcher will crash with a NullPointerException unless
     *     a LauncherListener has been registered for the solver.
     *
     *  2. The translation employed by the Z3_CE solver requires the
     *     problem submitted to the solver to be associated with a
     *     goal, which in turn is associated with a proof registered
     *     in the specification repository, in order to extract the
     *     type of the enclosing class associated with the
     *     problem/goal to be submitted to the SMT solver.  Since our
     *     queries are generated independently of any particular goal,
     *     the translation fails and (silently) interrupts the solver
     *     launch.  As a result, the solver seems to return an
     *     'unknown' result irrespective of the submitted query.  See
     *     the translateToCommand() method in the
     *     de.uka.ilkd.key.smt.SMTSolverImplementation class for more
     *     details.
     */
    // val models = for {
    //   solver <- problem.getSolvers.asScala
    //   // Only the Z3_CE API supports model extractions.
    //   if (solver.getType == SolverType.Z3_CE_SOLVER &&
    //       solver.getSocket.getQuery != null)
    // } yield solver.getSocket.getQuery.getModel
    val models = None

    // Get the final result
    (models.headOption, problem.getFinalResult)
  }
}

/** Companion of `SmtSolver`. */
object SmtSolver {

  // FIXME: this should just be an alias of
  // `SolverType.ALL_SOLVERS.asScala`, but that field seems to be
  // missing some solvers (CVC4, Z3_CE)...
  val allSolvers: List[SolverType] = {
    val solvers = SolverType.ALL_SOLVERS.asScala.toList
    // FIXME: Z3_CE not compatible with above launcher code...
    //SolverType.Z3_CE_SOLVER :: SolverType.CVC4_SOLVER :: solvers
    SolverType.CVC4_SOLVER :: solvers
  }

  def getSolverNames(): List[String] = {
    allSolvers.filter(_.isInstalled(false)).map(_.getName).toList
  }

  def getSolverType(name: String): Option[SolverType] = {
    allSolvers
      .find(_.getName == name)
      .filter(checkSolverType) // FIXME!
  }

  def checkSolverType(solverType: SolverType): Boolean = {
    val isInst = solverType.isInstalled(false)
    if (!isInst) {
      System.err.println(
        "ERROR: Solver not installed: " + solverType.getName)
      false
    } else {
      val res = solverType.checkForSupport
      if (!res) {
        System.err.println(
          "WARNING: Unsupported solver version (" + solverType.getName + "): " +
            solverType.getRawVersion)
        val versions = solverType.getSupportedVersions
        if (!versions.isEmpty) {
          val versionsStr = versions.mkString(", ")
          System.err.println(s"Supported versions: $versionsStr")
        }
        val info = solverType.getInfo
        if (info != null && !info.isEmpty)
          System.err.println(solverType.getInfo)
      }
      true // FIXME!
    }
  }

  lazy val defaultSolverType: SolverType = {

    // Prioritize Z3 when looking through installed solvers.
    val solvers =
      //SolverType.Z3_CE_SOLVER :: SolverType.Z3_SOLVER :: allSolvers //FIXME
      SolverType.Z3_SOLVER :: allSolvers
    val solverOpt =
      solvers.find(s => s.isInstalled(false) && checkSolverType(s))
    if (solverOpt.isEmpty) {
      System.err.println("ERROR: No supported SMT solvers found!")
      Timers.abort(1)
    }
    solverOpt.get
  }

  // Factory methods

  def apply(solverType: SolverType, proof: Proof): SmtSolver = {
    //if (!checkSolverType(solverType)) Timers.abort(1)
    new SmtSolver(solverType, proof)
  }
  def apply(proof: Proof): SmtSolver =
    SmtSolver(defaultSolverType, proof)
  def apply(name: String, proof: Proof): Option[SmtSolver] = {
    getSolverType(name).map(SmtSolver(_, proof))
  }
}


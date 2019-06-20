package se.gu
package minion

import scala.collection.JavaConverters._
import scala.collection.SortedSet

import scala.xml._

import java.{ util => ju }

import org.key_project.util.collection.ImmutableSLList

import de.uka.ilkd.key.control.{ DefaultUserInterfaceControl, KeYEnvironment }

import de.uka.ilkd.key.java.Services

import de.uka.ilkd.key.logic.Term
import de.uka.ilkd.key.logic.op.IProgramMethod

import de.uka.ilkd.key.proof.init.{
  AbstractOperationPO, FunctionalOperationContractPO, ProofInputException }

import de.uka.ilkd.key.settings.ProofSettings

import de.uka.ilkd.key.symbolic_execution.{
  ExecutionNodePreorderIterator, SymbolicExecutionTreeBuilder }
import de.uka.ilkd.key.symbolic_execution.model.{
  IExecutionMethodReturn, IExecutionTermination, ITreeSettings }
import de.uka.ilkd.key.symbolic_execution.po.ProgramMethodPO;
import de.uka.ilkd.key.symbolic_execution.profile.SymbolicExecutionJavaProfile;
import de.uka.ilkd.key.symbolic_execution.strategy.{
  CompoundStopCondition, ExecutedSymbolicExecutionTreeNodesStopCondition,
  SymbolicExecutionBreakpointStopCondition }
import de.uka.ilkd.key.symbolic_execution.strategy.breakpoint.ExceptionBreakpoint
import de.uka.ilkd.key.symbolic_execution.util.{
  SymbolicExecutionEnvironment, SymbolicExecutionUtil }

import de.uka.ilkd.key.util.MiscTools;

final class TermFormater(val services: Services, val settings: ITreeSettings) {
  def termBuilder = services.getTermBuilder
  def formatTerm(term: Term): String =
      SymbolicExecutionUtil.formatTerm(
        term, services, settings.isUseUnicode, settings.isUsePrettyPrinting)
  def formatTraceElem(traceElem: TraceElem): String = traceElem match {
    case BaseTm(t)   => formatTerm(t)
    case ArrayTm(ts) => ts.map(formatTraceElem).mkString("{ ", ", ", " }")
  }
  def formatTrace(trace: List[TraceElem]): String =
    trace.map(formatTraceElem).mkString(", ")
}

/**
 * Symbolically execute the first method in the first class of a Java
 * source file and output an XML file for use with the `DataMin.py`
 * script.
 *
 * @author Sandro Stucki (adopted from an example by Martin Hentschel)
 */
object Main {

  /**
   * Extract the logical characterization of a given method.
   *
   * @param env The KeY environment in which the symbolic execution
   *   takes place.
   * @param methodPO The the method to symbolically execute.
   * @param methodPO The a proof obligation associate with the method
   *   to symbolically execute.
   * @param builder The {@link SymbolicExecutionTreeBuilder} providing
   *   the root of the symbolic execution tree.
   */
  def getMethodCharac(
    args:     Arguments,
    env:      KeYEnvironment[DefaultUserInterfaceControl],
    method:   IProgramMethod,
    methodPO: AbstractOperationPO,
    builder:  SymbolicExecutionTreeBuilder): MethodCharac = {

    System.err.print("Processing symbolic execution paths...")
    System.err.flush()

    var numPathNodes: Int = 0

    // Extract the different paths.
    val iterator = new ExecutionNodePreorderIterator(builder.getStartNode);
    val paths    = new collection.mutable.ListBuffer[ProgPath]
    while (iterator.hasNext) {
      val n = iterator.next()
      numPathNodes += 1

      // We are only interested in the 'leaves' of the symbolic
      // execution tree, i.e. method return nodes associated with
      // 'method' and the subsequent termination nodes.
      val isRelevantRetNode =
        if (n.isInstanceOf[IExecutionMethodReturn]) {
          val rn = n.asInstanceOf[IExecutionMethodReturn]
          val cn = rn.getMethodCall
          val pm = cn.getProgramMethod
          pm == method
        } else false
      if (isRelevantRetNode) {
        val rn = n.asInstanceOf[IExecutionMethodReturn]
        try {
          // Check if the next node is a termination node.
          if (iterator.hasNext) {
            val n2 = iterator.next()
            if (n2.isInstanceOf[IExecutionTermination]) {
              // Yep! Register the path and reset the node counter.
              val tn = n2.asInstanceOf[IExecutionTermination]
              paths += ProgPath(rn, tn)
              numPathNodes = 0
            } else {
              // Nope, this must have been an internal method return
              // (i.e. from an inlined method).  Don't register the
              // path.
            }
          } else {
            System.err.println()
            System.err.println(
              "ERROR: return node not followed by termination node " +
                "in symbolic execution tree. Aborting symbolic execution...")
            Timers.abort(1)
          }
        } catch {
          case e: ProofInputException =>
            System.err.println(
              "ERROR: Exception during symbolic execution: " + e)
            Timers.abort(1)
        }

        System.err.print(".")
        System.err.flush()

      } else if (!n.isInstanceOf[IExecutionTermination] &&
        (n.getChildren.length == 0)) {

        // We expect every leaf node to be the an instance of
        // IExecutionTermination.  If this is not the case, then at
        // least one branch was cut off, probably as a result of
        //
        //  1. reaching the maximum depth, or
        //  2. an overly general loop invariant.

        val cp = ProgPath(method, n)
        System.err.println()
        System.err.println("WARNING: encountered prematurely terminated path.")
        System.err.print(
          "A path has been cut off before it was properly terminated")

        if (numPathNodes < args.maxDepth) {
          System.err.println(
            ", probably due to an overly general loop invariant")
          System.err.println(s" - path condition : '${cp.cond}',")
          System.err.println(s" - path depth     : $numPathNodes steps.")
        } else {
          System.err.println(
            " because the maximum path depth was reached (" + args.maxDepth +
              " execution steps).")
          System.err.println("Are you using the '-u' option? If not, " +
            "consider increasing the execution depth using the '-d' option.")
        }
        paths += cp
        System.err.print("Proceeding with remaining execution paths...")
      }
    }
    System.err.println(" done.")

    MethodCharac(method, env.getServices, paths.result())
  }

  /**
   * The program entry point.
   * @param args The start parameters.
   */
  def main(args: Array[String]) {

    // Parse command line arguments and input files.
    val as = Arguments(args)
    Timers.init(as)

    // Path to the source code folder/file or to a *.proof file
    val location = as.srcFile
    // Optionally: Additional specifications for API classes
    val classPaths = null
    // Optionally: Different default specifications for Java API
    val bootClassPath = null
    // Optionally: Additional includes to consider
    val includes = null

    try {
      // Ensure that Taclets are parsed
      if (!ProofSettings.isChoiceSettingInitialised) {
        System.err.print("Loading KeY taclets...")
        System.err.flush()
        val env = KeYEnvironment.load(
          location, classPaths, bootClassPath, includes)
        env.dispose();
        System.err.println(" done.")
      }

      // Set Taclet options
      val choiceSettings = ProofSettings.DEFAULT_SETTINGS.getChoiceSettings
      val oldSettings = choiceSettings.getDefaultChoices
      val newSettings = new ju.HashMap[String, String](oldSettings)
      newSettings.putAll(MiscTools.getDefaultTacletOptions)
      choiceSettings.setDefaultChoices(newSettings)

      // Load source code
      System.err.print(s"Loading source code from '${as.srcFile.toPath}'...")
      System.err.flush()
      val env = KeYEnvironment.load(
        SymbolicExecutionJavaProfile.getDefaultInstance,
        location, classPaths, bootClassPath, includes, true)
      // env.getLoadedProof returns performed proof if a *.proof file is loaded
      System.err.println(" done.")

      val javaInfo = env.getJavaInfo

      try {

        // Find the class containing the method to symbolically execute
        val classType = javaInfo.getKeYJavaType(as.className)
        if (classType == null) {
          System.err.println(s"ERROR: could not find class '${as.className}'.")
          Timers.abort(1)
        }

        // Find method to symbolically execute
        val pm = if (as.mtdName == "") {
          // pick the first non-implicit, non-abstract method
          val mtds = javaInfo.getAllProgramMethodsLocallyDeclared(classType)
          val firstMtd = mtds.asScala.find(m => !m.isImplicit && !m.isAbstract)
          firstMtd.getOrElse {
            System.err.println(
              s"ERROR: no methods found in class '${as.className}'.")
            Timers.abort(1)
            null
          }
        } else {
          // // FIXME: requires parsing signature?
          // val pm = javaInfo.getProgramMethod(
          //   classType,
          //   as.mtdName,
          //   ImmutableSLList.nil().append(classType),
          //   classType)
          val mtds = javaInfo.getAllProgramMethodsLocallyDeclared(classType)
          val mtd = mtds.asScala find { m =>
            m.getName == as.mtdName && !m.isAbstract
          }
          mtd.getOrElse {
            System.err.println(
              s"ERROR: could not find method '${as.mtdName}' " +
                s"in class '${as.className}'.")
            Timers.abort(1)
            null
          }
        }

        // Get the method contracts, if there are any.
        val specRepo  = env.getSpecificationRepository
        val contracts = specRepo.getOperationContracts(classType, pm)
        val po        = if (contracts.isEmpty) {

          // No method contracts, instantiate proof obligation for
          // symbolic execution of the program method (Java semantics)
          new ProgramMethodPO(
            env.getInitConfig,
            "Symbolic Execution of: " + pm,
            pm,
            null,  // An optional precondition (as a JML string)
            true,  // Needs to be true for symbolic execution
            true); // Needs to be true for symbolic execution

        } else {

          // If there are multiple contracts, combine them into one.
          val contract  = specRepo.combineOperationContracts(contracts)
          // FIXME: should we include inherited contracts?
          //val inhContracts = specRepo.getInheritedContracts(contract)

          // Instantiate PO for verification (JML semantics)
          new FunctionalOperationContractPO(
            env.getInitConfig,
            contract,
            true,     // Needs to be true for symbolic execution
            true);    // Needs to be true for symbolic execution
        }

        // PO for symbolic execution of some statements within a
        // method (Java semantics)
        // po = new ProgramMethodSubsetPO(...);

        val proof = env.createProof(po);

        // Create symbolic execution tree builder
        val builder = new SymbolicExecutionTreeBuilder(
          proof,
          false, // Merge branch conditions
          false, // Use Unicode?
          true,  // Use Pretty Printing?
          true,  // Variables are collected from updates instead of the
                 // visible type structure
          true); // Simplify conditions
        builder.analyse();

        // Create an SymbolicExecutionEnvironment which provides
        // access to all relevant objects for symbolic execution
        val symbolicEnv =
          new SymbolicExecutionEnvironment[DefaultUserInterfaceControl](
            env, builder)

        // Configure strategy for full exploration
        SymbolicExecutionUtil.initializeStrategy(builder)
        SymbolicExecutionEnvironment.configureProofForSymbolicExecution(
          proof,
          as.maxDepth,
          false,  // true to apply method contracts instead of inlining,
          !as.unrollLoops, // true to apply loop invariants instead of unrolling,
          false,  // true to apply block contracts instead of expanding,
          false,  // true to hide branch conditions caused by symbolic
                  // execution within modalities not of interest,
          false)  // true to perform alias checks during symbolic execution.

        // // Optionally, add a more advanced stop conditions like breakpoints

        // // Stop after 'maxDepth' nodes have been explored on each branch.
        // val stopCondition = new CompoundStopCondition
        // stopCondition.addChildren(
        //   new ExecutedSymbolicExecutionTreeNodesStopCondition(maxDepth + 1))

        // // Perform only a step over
        // stopCondition.addChildren(
        //   new StepOverSymbolicExecutionTreeNodesStopCondition())

        // // Perform only a step return
        // stopCondition.addChildren(
        //   new StepReturnSymbolicExecutionTreeNodesStopCondition())

        // // Stop at specified breakpoints
        // val breakpoint = new ExceptionBreakpoint(
        //   proof, "java.lang.NullPointerException", true, true, true, true, 1)
        // stopCondition.addChildren(
        //   new SymbolicExecutionBreakpointStopCondition(breakpoint))

        // val proofSettings = proof.getSettings.getStrategySettings
        // proofSettings.setCustomApplyStrategyStopCondition(stopCondition)

        Timers.start()
        System.err.print(s"Symbolically executing program...")
        System.err.flush()

        // Perform strategy which will stop at max depth.
        symbolicEnv.getProofControl.startAndWaitForAutoMode(proof)
        builder.analyse()

        System.err.println(" done.")
        Timers.stopSymbExec()

        // // Perform strategy again to complete symbolic execution tree
        // symbolicEnv.getProofControl.startAndWaitForAutoMode(proof)
        // builder.analyse()

        // Extract the method characterization from the tree.
        Timers.start()
        val charac = getMethodCharac(as, env, pm, po, builder)
        Timers.stopExtract()

        val services = env.getServices
        val treeSettings = builder.getStartNode.getSettings
        val partition = if (as.monolithic) {
          // Monolithic minimality: all parameters are grouped
          // together in a singleton partition.
          List(SortedSet(0 until charac.params.size: _*))
        } else {
          // Distributed minimality: every parameter forms its own partition.
          for (i <- 0 until charac.params.size) yield SortedSet(i)
        }
        val solver = SmtSolver(as.solverType, proof)
        val monitor = new Monitor(as, solver, services, treeSettings)
        Timers.start()
        if (as.eager) {
          System.err.println("Monitoring eagerly.")
          if (as.lazyMon) {
            System.err.println("WARNING: '--lazy' ignored in eager mode...")
          }
          monitor.monitorEagerly(charac, partition.toList)
        } else if (as.lazyMon) {
          System.err.println("Monitoring lazily.")
          monitor.monitorLazily(charac, partition.toList)
        } else {
          monitor.monitor(charac, partition.toList)
        }
        Timers.stopMonitor()
      } finally {
        // Ensure always that all instances of KeYEnvironment are disposed
        env.dispose()

        Timers.stopAll()
        Timers.printTimers()
      }
    } catch {
      case e: Exception =>
        System.out.flush();
        System.err.println();
        System.err.println(s"Exception at '$location':");
        e.printStackTrace(System.err);
    }
  }
}
